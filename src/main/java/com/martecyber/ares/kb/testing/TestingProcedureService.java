package com.martecyber.ares.kb.testing;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.testing.dto.TestingProcedureDto;
import com.martecyber.ares.kb.testing.dto.TestingProcedureExternalRefDto;
import com.martecyber.ares.kb.testing.dto.TestingProcedureGuidePointRefDto;
import jakarta.transaction.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TestingProcedureService {

    private static final Set<String> REF_TYPES = Set.of("cve", "cwe", "capec", "attack", "owasp");
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final TestingProcedureRepository procedureRepo;
    private final TestingProcedureGuidePointRepository guidePointLinkRepo;
    private final TestingProcedureExternalRefRepository externalRefRepo;
    private final TestingGuidePointRepository pointRepo;
    private final TestingGuideRepository guideRepo;

    public TestingProcedureService(TestingProcedureRepository procedureRepo,
                                   TestingProcedureGuidePointRepository guidePointLinkRepo,
                                   TestingProcedureExternalRefRepository externalRefRepo,
                                   TestingGuidePointRepository pointRepo,
                                   TestingGuideRepository guideRepo) {
        this.procedureRepo = procedureRepo;
        this.guidePointLinkRepo = guidePointLinkRepo;
        this.externalRefRepo = externalRefRepo;
        this.pointRepo = pointRepo;
        this.guideRepo = guideRepo;
    }

    public Page<TestingProcedureDto> list(String q, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        String query = (q != null && !q.isBlank()) ? q.trim() : null;
        Page<TestingProcedure> src = query != null ? procedureRepo.search(query, p)
                                                   : procedureRepo.findAllByOrderByTitleAsc(p);
        return src.map(TestingProcedureDto::summary);
    }

    public TestingProcedureDto get(Long id) {
        TestingProcedure proc = procedureRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("testing_procedure", id));
        return toDetail(proc);
    }

    /** Procedures linked to a testing-guide point — used by the project checklist. */
    public List<TestingProcedureDto> listByGuidePoint(Long pointId) {
        return procedureRepo.findByGuidePointId(pointId).stream()
            .map(TestingProcedureDto::summary).toList();
    }

    @Transactional
    public TestingProcedureDto create(ProcedureRequest req) {
        requireTitle(req);
        OffsetDateTime now = OffsetDateTime.now();
        TestingProcedure proc = new TestingProcedure();
        proc.setTitle(req.title().trim());
        proc.setContent(req.content());
        proc.setTags(serializeTags(req.tags()));
        proc.setCreatorId(currentUserId());
        proc.setCreatedAt(now);
        proc.setUpdatedAt(now);
        procedureRepo.save(proc);
        replaceLinks(proc.getId(), req);
        return toDetail(proc);
    }

    @Transactional
    public TestingProcedureDto update(Long id, ProcedureRequest req) {
        TestingProcedure proc = procedureRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("testing_procedure", id));
        requireTitle(req);
        proc.setTitle(req.title().trim());
        proc.setContent(req.content());
        proc.setTags(serializeTags(req.tags()));
        proc.setUpdatedAt(OffsetDateTime.now());
        procedureRepo.save(proc);
        replaceLinks(id, req);
        return toDetail(proc);
    }

    @Transactional
    public void delete(Long id) {
        if (!procedureRepo.existsById(id)) throw NotFoundException.of("testing_procedure", id);
        procedureRepo.deleteById(id); // junction rows cascade via FK
    }

    @Transactional
    public void deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            if (procedureRepo.existsById(id)) procedureRepo.deleteById(id);
        }
    }

    // ── Export / import ──────────────────────────────────────────────────────────
    // Export reuses ProcedureRequest's own shape as the file format, so a round-tripped
    // export→import needs no separate DTO or mapping on the way back in. guidePointIds
    // are exported as raw ids (same convention FindingTemplateService uses for its
    // reference ids) — re-importing into a different environment only round-trips
    // cleanly if those guide-point ids still resolve there.

    public ProcedureRequest exportOne(Long id) {
        TestingProcedureDto dto = get(id);
        List<Long> guidePointIds = dto.guidePoints() == null ? List.of()
            : dto.guidePoints().stream().map(TestingProcedureGuidePointRefDto::guidePointId).toList();
        List<ExternalRefRequest> refs = dto.externalRefs() == null ? List.of()
            : dto.externalRefs().stream()
                .map(r -> new ExternalRefRequest(r.refType(), r.refKey(), r.refLabel())).toList();
        return new ProcedureRequest(dto.title(), dto.content(), dto.tags(), guidePointIds, refs);
    }

    /** A .json file per procedure, zipped together — used for the "export selected" bulk
     *  action. Zip entry names are best-effort human-readable (sanitized title + id) and
     *  otherwise carry no meaning; import re-parses each entry purely by content. */
    public byte[] exportZip(List<Long> ids) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(baos)) {
            for (Long id : ids) {
                ProcedureRequest data = exportOne(id);
                byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
                String safeTitle = data.title().replaceAll("[^a-zA-Z0-9-]+", "_");
                zos.putNextEntry(new java.util.zip.ZipEntry(safeTitle + "-" + id + ".json"));
                zos.write(json);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build export zip", e);
        }
    }

    /** Accepts any mix of .json files (one procedure each) and .zip files (a batch of
     *  .json entries, as produced by exportZip) in a single upload. Best-effort: one bad
     *  file or entry is reported in the result rather than aborting the whole import. */
    @Transactional
    public TestingProcedureImportResult importFiles(List<MultipartFile> files) {
        int imported = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (var file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            try {
                if (name.toLowerCase().endsWith(".zip")) {
                    try (var zis = new java.util.zip.ZipInputStream(file.getInputStream())) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".json")) continue;
                            byte[] bytes = zis.readAllBytes();
                            try {
                                create(MAPPER.readValue(bytes, ProcedureRequest.class));
                                imported++;
                            } catch (Exception e) {
                                errors.add(name + "/" + entry.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                } else {
                    create(MAPPER.readValue(file.getBytes(), ProcedureRequest.class));
                    imported++;
                }
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return new TestingProcedureImportResult(imported, errors);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void replaceLinks(Long procedureId, ProcedureRequest req) {
        guidePointLinkRepo.deleteByProcedureId(procedureId);
        externalRefRepo.deleteByProcedureId(procedureId);

        if (req.guidePointIds() != null) {
            for (Long pointId : req.guidePointIds().stream().distinct().toList()) {
                if (pointId != null && pointRepo.existsById(pointId)) {
                    guidePointLinkRepo.save(new TestingProcedureGuidePoint(procedureId, pointId));
                }
            }
        }
        if (req.externalRefs() != null) {
            for (ExternalRefRequest r : req.externalRefs()) {
                if (r == null || r.refType() == null || r.refKey() == null
                    || r.refType().isBlank() || r.refKey().isBlank()) continue;
                String type = r.refType().trim().toLowerCase();
                if (!REF_TYPES.contains(type)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown external ref type: " + r.refType());
                }
                externalRefRepo.save(new TestingProcedureExternalRef(
                    procedureId, type, r.refKey().trim(), blankToNull(r.refLabel())));
            }
        }
    }

    private TestingProcedureDto toDetail(TestingProcedure proc) {
        var pointLinks = guidePointLinkRepo.findByProcedureId(proc.getId());
        List<Long> pointIds = pointLinks.stream()
            .map(TestingProcedureGuidePoint::getGuidePointId).toList();

        Map<Long, TestingGuidePoint> pointsById = pointRepo.findAllById(pointIds).stream()
            .collect(Collectors.toMap(TestingGuidePoint::getId, x -> x));
        Set<Long> guideIds = pointsById.values().stream()
            .map(TestingGuidePoint::getGuideId).collect(Collectors.toSet());
        Map<Long, String> guideNames = guideRepo.findAllById(guideIds).stream()
            .collect(Collectors.toMap(TestingGuide::getId, TestingGuide::getName));

        List<TestingProcedureGuidePointRefDto> guidePoints = pointIds.stream()
            .map(pointsById::get)
            .filter(x -> x != null)
            .map(pt -> new TestingProcedureGuidePointRefDto(
                pt.getId(), pt.getTitle(), pt.getGuideId(),
                guideNames.getOrDefault(pt.getGuideId(), "")))
            .toList();

        List<TestingProcedureExternalRefDto> externalRefs = externalRefRepo.findByProcedureId(proc.getId())
            .stream().map(TestingProcedureExternalRefDto::from).toList();

        return TestingProcedureDto.detail(proc, guidePoints, externalRefs);
    }

    private static void requireTitle(ProcedureRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
    }

    private static String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        try { return MAPPER.writeValueAsString(tags.stream().map(String::trim).filter(t -> !t.isBlank()).distinct().toList()); }
        catch (Exception e) { return "[]"; }
    }

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }

    private static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    // ── request records ──────────────────────────────────────────────────────

    public record ProcedureRequest(
        String title,
        String content,
        List<String> tags,
        List<Long> guidePointIds,
        List<ExternalRefRequest> externalRefs
    ) {}

    public record ExternalRefRequest(String refType, String refKey, String refLabel) {}
}
