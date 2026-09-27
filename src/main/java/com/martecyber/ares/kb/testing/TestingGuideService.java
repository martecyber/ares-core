package com.martecyber.ares.kb.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.testing.dto.TestingGuideDto;
import com.martecyber.ares.kb.testing.dto.TestingGuidePointDto;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class TestingGuideService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TestingGuideRepository guideRepo;
    private final TestingGuidePointRepository pointRepo;

    public TestingGuideService(TestingGuideRepository guideRepo, TestingGuidePointRepository pointRepo) {
        this.guideRepo = guideRepo;
        this.pointRepo = pointRepo;
    }

    // ── Guides ─────────────────────────────────────────────────────────────

    public Page<TestingGuideDto> list(int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return guideRepo.findAllByOrderByNameAsc(p)
            .map(g -> TestingGuideDto.summary(g, pointRepo.countByGuideId(g.getId())));
    }

    public TestingGuideDto get(Long id) {
        TestingGuide g = guideRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("testing_guide", id));
        return TestingGuideDto.detail(g, points(id));
    }

    @Transactional
    public TestingGuideDto create(GuideRequest req) {
        requireName(req);
        OffsetDateTime now = OffsetDateTime.now();
        TestingGuide g = new TestingGuide();
        g.setName(req.name().trim());
        g.setDescription(blankToNull(req.description()));
        g.setEnabled(req.enabled() == null ? true : req.enabled());
        g.setCreatorId(currentUserId());
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        guideRepo.save(g);
        return TestingGuideDto.detail(g, List.of());
    }

    @Transactional
    public TestingGuideDto update(Long id, GuideRequest req) {
        TestingGuide g = guideRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("testing_guide", id));
        requireName(req);
        g.setName(req.name().trim());
        g.setDescription(blankToNull(req.description()));
        if (req.enabled() != null) g.setEnabled(req.enabled());
        g.setUpdatedAt(OffsetDateTime.now());
        guideRepo.save(g);
        return TestingGuideDto.detail(g, points(id));
    }

    @Transactional
    public void delete(Long id) {
        if (!guideRepo.existsById(id)) throw NotFoundException.of("testing_guide", id);
        guideRepo.deleteById(id); // points cascade via FK
    }

    /** Import a guide from exported JSON — creates a brand-new guide + all points. */
    @Transactional
    public TestingGuideDto importGuide(TestingGuideController.ImportGuideRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "name is required");
        }
        OffsetDateTime now = OffsetDateTime.now();
        TestingGuide g = new TestingGuide();
        g.setName(req.name().trim());
        g.setDescription(blankToNull(req.description()));
        g.setEnabled(true);
        g.setCreatorId(currentUserId());
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        guideRepo.save(g);
        if (req.points() != null) {
            int order = 0;
            for (var p : req.points()) {
                if (p == null || p.title() == null || p.title().isBlank()) continue;
                TestingGuidePoint pt = new TestingGuidePoint();
                pt.setGuideId(g.getId());
                pt.setTitle(p.title().trim());
                pt.setDescription(blankToNull(p.description()));
                pt.setSortOrder(p.sortOrder());
                pt.setCreatedAt(now);
                pt.setUpdatedAt(now);
                pointRepo.save(pt);
                order++;
            }
        }
        return TestingGuideDto.detail(g, points(g.getId()));
    }

    // ── Export / import ──────────────────────────────────────────────────────────
    // Export reuses ImportGuideRequest's own shape as the file format, so a
    // round-tripped export→import needs no separate DTO or mapping on the way back in.

    public TestingGuideController.ImportGuideRequest exportOne(Long id) {
        TestingGuide g = guideRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("testing_guide", id));
        var pts = points(id).stream()
            .map(p -> new TestingGuideController.ImportPointRequest(p.title(), p.description(), p.sortOrder()))
            .toList();
        return new TestingGuideController.ImportGuideRequest(g.getName(), g.getDescription(), pts);
    }

    /** A .json file per guide, zipped together — used for the "export selected" bulk
     *  action. Zip entry names are best-effort human-readable (sanitized name + id) and
     *  otherwise carry no meaning; import re-parses each entry purely by content. */
    public byte[] exportZip(List<Long> ids) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(baos)) {
            for (Long id : ids) {
                TestingGuideController.ImportGuideRequest data = exportOne(id);
                byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
                String safeName = data.name().replaceAll("[^a-zA-Z0-9-]+", "_");
                zos.putNextEntry(new java.util.zip.ZipEntry(safeName + "-" + id + ".json"));
                zos.write(json);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build export zip", e);
        }
    }

    /** Accepts any mix of .json files (one guide each) and .zip files (a batch of .json
     *  entries, as produced by exportZip) in a single upload. Best-effort: one bad file
     *  or entry is reported in the result rather than aborting the whole import. */
    @Transactional
    public TestingGuideImportResult importFiles(List<org.springframework.web.multipart.MultipartFile> files) {
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
                                importGuide(MAPPER.readValue(bytes, TestingGuideController.ImportGuideRequest.class));
                                imported++;
                            } catch (Exception e) {
                                errors.add(name + "/" + entry.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                } else {
                    importGuide(MAPPER.readValue(file.getBytes(), TestingGuideController.ImportGuideRequest.class));
                    imported++;
                }
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return new TestingGuideImportResult(imported, errors);
    }

    // ── Points ─────────────────────────────────────────────────────────────

    public List<TestingGuidePointDto> points(Long guideId) {
        return pointRepo.findByGuideIdOrderBySortOrderAscIdAsc(guideId).stream()
            .map(TestingGuidePointDto::from).toList();
    }

    @Transactional
    public TestingGuidePointDto addPoint(Long guideId, PointRequest req) {
        TestingGuide g = guideRepo.findById(guideId)
            .orElseThrow(() -> NotFoundException.of("testing_guide", guideId));
        requireTitle(req);
        OffsetDateTime now = OffsetDateTime.now();
        int nextOrder = pointRepo.findByGuideIdOrderBySortOrderAscIdAsc(guideId).stream()
            .mapToInt(TestingGuidePoint::getSortOrder).max().orElse(-1) + 1;
        TestingGuidePoint p = new TestingGuidePoint();
        p.setGuideId(g.getId());
        p.setTitle(req.title().trim());
        p.setDescription(blankToNull(req.description()));
        p.setSortOrder(req.sortOrder() != null ? req.sortOrder() : nextOrder);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        touchGuide(g);
        return TestingGuidePointDto.from(pointRepo.save(p));
    }

    @Transactional
    public TestingGuidePointDto updatePoint(Long guideId, Long pointId, PointRequest req) {
        TestingGuidePoint p = requirePoint(guideId, pointId);
        requireTitle(req);
        p.setTitle(req.title().trim());
        p.setDescription(blankToNull(req.description()));
        if (req.sortOrder() != null) p.setSortOrder(req.sortOrder());
        p.setUpdatedAt(OffsetDateTime.now());
        touchGuideById(guideId);
        return TestingGuidePointDto.from(pointRepo.save(p));
    }

    @Transactional
    public void deletePoint(Long guideId, Long pointId) {
        TestingGuidePoint p = requirePoint(guideId, pointId);
        pointRepo.delete(p);
        touchGuideById(guideId);
    }

    /** Reorders all points of a guide to the given id sequence. */
    @Transactional
    public List<TestingGuidePointDto> reorder(Long guideId, List<Long> orderedIds) {
        var byId = pointRepo.findByGuideIdOrderBySortOrderAscIdAsc(guideId).stream()
            .collect(java.util.stream.Collectors.toMap(TestingGuidePoint::getId, p -> p));
        int order = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (Long id : orderedIds) {
            TestingGuidePoint p = byId.get(id);
            if (p == null) continue;
            p.setSortOrder(order++);
            p.setUpdatedAt(now);
            pointRepo.save(p);
        }
        touchGuideById(guideId);
        return points(guideId);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private TestingGuidePoint requirePoint(Long guideId, Long pointId) {
        return pointRepo.findById(pointId)
            .filter(p -> p.getGuideId().equals(guideId))
            .orElseThrow(() -> NotFoundException.of("testing_guide_point", pointId));
    }

    private void touchGuideById(Long guideId) {
        guideRepo.findById(guideId).ifPresent(this::touchGuide);
    }

    private void touchGuide(TestingGuide g) {
        g.setUpdatedAt(OffsetDateTime.now());
        guideRepo.save(g);
    }

    private static void requireName(GuideRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
    }

    private static void requireTitle(PointRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
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

    public record GuideRequest(String name, String description, Boolean enabled) {}

    public record PointRequest(String title, String description, Integer sortOrder) {}
}
