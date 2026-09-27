package com.martecyber.ares.findings.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingFieldType;
import com.martecyber.ares.findings.FindingFieldTypeRepository;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingScoreRepository;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FindingTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FindingTemplateRepository repo;
    private final FindingTemplateFieldRepository fieldRepo;
    private final FindingTemplateScoreRepository scoreRepo;
    private final ReferenceEntryRepository referenceRepo;
    private final ReferenceCatalogRepository catalogRepo;
    private final FindingRepository findingRepo;
    private final FindingScoreRepository findingScoreRepo;
    private final FindingFieldTypeRepository fieldTypeRepo;
    private final FindingTemplateAqlRegistry aqlRegistry;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final FindingTemplateTagRepository tagRepo;
    private final com.martecyber.ares.tags.TagRepository tagCatalogRepo;

    public FindingTemplateService(FindingTemplateRepository repo,
                                   FindingTemplateFieldRepository fieldRepo,
                                   FindingTemplateScoreRepository scoreRepo,
                                   ReferenceEntryRepository referenceRepo,
                                   ReferenceCatalogRepository catalogRepo,
                                   FindingRepository findingRepo,
                                   FindingScoreRepository findingScoreRepo,
                                   FindingFieldTypeRepository fieldTypeRepo,
                                   FindingTemplateAqlRegistry aqlRegistry,
                                   WorkflowEventDispatcher workflowEventDispatcher,
                                   FindingTemplateTagRepository tagRepo,
                                   com.martecyber.ares.tags.TagRepository tagCatalogRepo) {
        this.repo = repo;
        this.fieldRepo = fieldRepo;
        this.scoreRepo = scoreRepo;
        this.referenceRepo = referenceRepo;
        this.catalogRepo = catalogRepo;
        this.findingRepo = findingRepo;
        this.findingScoreRepo = findingScoreRepo;
        this.fieldTypeRepo = fieldTypeRepo;
        this.aqlRegistry = aqlRegistry;
        this.workflowEventDispatcher = workflowEventDispatcher;
        this.tagRepo = tagRepo;
        this.tagCatalogRepo = tagCatalogRepo;
    }

    public Page<FindingTemplateDto> list(String q, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        String cleanQ = q != null && !q.isBlank() ? q.trim() : null;
        if (cleanQ == null) {
            return withTags(repo.findAllByOrderByCreatedAtDesc(p).map(t -> toDto(t, List.of(), List.of(), List.of())));
        }
        return withTags(repo.search("%" + cleanQ.toLowerCase() + "%", p).map(t -> toDto(t, List.of(), List.of(), List.of())));
    }

    /**
     * AQL-driven listing (AQL implementation plan, Phase 4) — coexists with {@link #list} rather
     * than replacing it, same "discrete params ignored when aql present" coexistence rule used
     * everywhere else. No org/project scope predicate to AND in here — FindingTemplate is a
     * platform-wide catalog entity, same access model as {@link #list}.
     */
    public Page<FindingTemplateDto> listByAql(String aql, String sortBy, String sortDir, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        var sort = "asc".equalsIgnoreCase(sortDir)
            ? org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, buildSortColumn(sortBy))
            : org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, buildSortColumn(sortBy));

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        com.martecyber.ares.aql.compile.AqlPlanner.validate(node, aqlRegistry);
        var spec = new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry).compile(node);

        return withTags(repo.findAll(spec, PageRequest.of(p, s, sort))
            .map(t -> toDto(t, List.of(), List.of(), List.of())));
    }

    private static String buildSortColumn(String sortBy) {
        return switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "title" -> "title";
            case "severity" -> "severity";
            default -> "createdAt";
        };
    }

    /** Batch-attaches tags to a page of already-built DTOs — one query for the whole page. */
    private Page<FindingTemplateDto> withTags(Page<FindingTemplateDto> page) {
        List<Long> ids = page.getContent().stream().map(FindingTemplateDto::id).toList();
        Map<Long, List<com.martecyber.ares.tags.TagDto>> byTemplate = loadTagsByTemplateIds(ids);
        return page.map(dto -> dto.withTags(byTemplate.getOrDefault(dto.id(), List.of())));
    }

    private Map<Long, List<com.martecyber.ares.tags.TagDto>> loadTagsByTemplateIds(List<Long> templateIds) {
        if (templateIds.isEmpty()) return Map.of();
        Map<Long, List<com.martecyber.ares.tags.TagDto>> byTemplate = new java.util.HashMap<>();
        for (FindingTemplateTagRepository.FindingTemplateTagRow row : tagRepo.findTagsForTemplateIds(templateIds)) {
            byTemplate.computeIfAbsent(row.getTemplateId(), k -> new java.util.ArrayList<>())
                .add(new com.martecyber.ares.tags.TagDto(row.getId(), null, row.getName(), row.getColor()));
        }
        return byTemplate;
    }

    public FindingTemplateDto get(Long id) {
        FindingTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding_template", id));
        List<FindingTemplateDto.FieldDto> fields = fieldRepo.findByTemplateId(id).stream()
            .map(f -> new FindingTemplateDto.FieldDto(f.getId(), f.getTypeId(), f.getFieldText(), f.getCreatedAt(), f.getUpdatedAt()))
            .toList();
        List<FindingTemplateDto.ScoreDto> scores = scoreRepo.findByTemplateId(id).stream()
            .map(s -> new FindingTemplateDto.ScoreDto(s.getId(), s.getTypeId(), s.getScore(), s.getMetadata(), s.isDefault(), s.getSsvcLeafNodeId(), s.getCreatedAt(), s.getUpdatedAt()))
            .toList();
        List<FindingTemplateDto.ReferenceDto> references = buildReferenceDtos(referenceRepo.findByTemplateId(id));
        List<com.martecyber.ares.tags.TagDto> tags = loadTagsByTemplateIds(List.of(id)).getOrDefault(id, List.of());
        return toDto(t, fields, scores, references).withTags(tags);
    }

    /** Assigns an existing tag to a finding template — FindingTemplate has no organization of its
     *  own, so only platform tags (no organization) can ever be assigned here. */
    @Transactional
    public FindingTemplateDto assignTag(Long templateId, Long tagId) {
        if (!repo.existsById(templateId)) throw NotFoundException.of("finding_template", templateId);
        var tag = tagCatalogRepo.findById(tagId).orElseThrow(() -> NotFoundException.of("tag", tagId));
        if (tag.getOrganizationId() != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Only platform tags can be assigned to a finding template");
        }
        tagRepo.assign(templateId, tagId);
        return get(templateId);
    }

    @Transactional
    public FindingTemplateDto unassignTag(Long templateId, Long tagId) {
        tagRepo.unassign(templateId, tagId);
        return get(templateId);
    }

    @Transactional
    public FindingTemplateDto create(CreateFindingTemplateRequest req) {
        // @NotBlank on the controller's @Valid @RequestBody already guarantees this for
        // that path, but create() is also called directly from bulk import (arbitrary
        // uploaded JSON, no Bean Validation) — guard here so a malformed entry fails with
        // a clear per-entry error instead of an NPE that aborts the whole batch.
        if (req.title() == null || req.title().isBlank())
            throw new IllegalArgumentException("Title is required");
        Long creatorId = currentUserId();
        FindingTemplate t = new FindingTemplate();
        // Severity is derived from the default score below, once scores are saved —
        // req.severity() is intentionally ignored (kept only for deserialization compat).
        t.setSeverity(null);
        t.setTitle(req.title().trim());
        t.setCreatorId(creatorId);
        OffsetDateTime now = OffsetDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        FindingTemplate saved = repo.save(t);

        if (req.fields() != null) {
            for (var f : req.fields()) {
                if (f.typeId() == null) continue;
                FindingTemplateField field = new FindingTemplateField();
                field.setTemplateId(saved.getId());
                field.setTypeId(f.typeId());
                field.setFieldText(f.fieldText());
                field.setCreatedAt(now);
                field.setUpdatedAt(now);
                fieldRepo.save(field);
            }
        }

        if (req.scores() != null && !req.scores().isEmpty()) {
            boolean hasDefault = req.scores().stream().anyMatch(s -> Boolean.TRUE.equals(s.isDefault()));
            int i = 0;
            for (var s : req.scores()) {
                if (s.typeId() == null || s.score() == null) continue;
                FindingTemplateScore score = new FindingTemplateScore();
                score.setTemplateId(saved.getId());
                score.setTypeId(s.typeId());
                score.setScore(java.math.BigDecimal.valueOf(s.score()));
                score.setMetadata(s.metadata());
                score.setSsvcLeafNodeId(s.ssvcLeafNodeId());
                score.setDefault(hasDefault ? Boolean.TRUE.equals(s.isDefault()) : i == 0);
                score.setCreatedAt(now);
                score.setUpdatedAt(now);
                scoreRepo.save(score);
                i++;
            }
            applyDerivedSeverity(saved);
            repo.save(saved);
        }

        // Save references via the join table
        if (req.referenceIds() != null && !req.referenceIds().isEmpty()) {
            List<ReferenceEntry> refs = referenceRepo.findByIdIn(req.referenceIds());
            for (ReferenceEntry ref : refs) {
                ref.getFindingTemplates().add(saved);
                referenceRepo.save(ref);
            }
        }

        List<FindingTemplateDto.FieldDto> fields = fieldRepo.findByTemplateId(saved.getId()).stream()
            .map(f -> new FindingTemplateDto.FieldDto(f.getId(), f.getTypeId(), f.getFieldText(), f.getCreatedAt(), f.getUpdatedAt()))
            .toList();
        List<FindingTemplateDto.ScoreDto> scores = scoreRepo.findByTemplateId(saved.getId()).stream()
            .map(s -> new FindingTemplateDto.ScoreDto(s.getId(), s.getTypeId(), s.getScore(), s.getMetadata(), s.isDefault(), s.getSsvcLeafNodeId(), s.getCreatedAt(), s.getUpdatedAt()))
            .toList();
        List<FindingTemplateDto.ReferenceDto> references = buildReferenceDtos(referenceRepo.findByTemplateId(saved.getId()));

        workflowEventDispatcher.onFindingTemplateCreated(saved);
        return toDto(saved, fields, scores, references);
    }

    @Transactional
    public FindingTemplateDto updateMeta(Long id, String title, String severity) {
        FindingTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding_template", id));
        if (title != null && !title.isBlank()) t.setTitle(title.trim());
        // severity intentionally ignored — derived only, from the default score
        // (see applyDerivedSeverity / replaceScores). Param kept for API compatibility.
        t.setUpdatedAt(OffsetDateTime.now());
        FindingTemplate saved = repo.save(t);
        workflowEventDispatcher.onFindingTemplateUpdated(saved);
        return get(id);
    }

    @Transactional
    public FindingTemplateDto updateField(Long templateId, Long fieldId, String fieldText) {
        FindingTemplateField field = fieldRepo.findById(fieldId)
            .orElseThrow(() -> NotFoundException.of("field", fieldId));
        if (!field.getTemplateId().equals(templateId))
            throw NotFoundException.of("field", fieldId);
        field.setFieldText(fieldText);
        field.setUpdatedAt(OffsetDateTime.now());
        fieldRepo.save(field);
        repo.findById(templateId).ifPresent(t -> { t.setUpdatedAt(OffsetDateTime.now()); repo.save(t); });
        return get(templateId);
    }

    @Transactional
    public FindingTemplateDto addField(Long templateId, Long typeId, String fieldText) {
        FindingTemplate t = repo.findById(templateId).orElseThrow(() -> NotFoundException.of("finding_template", templateId));
        boolean alreadyPresent = fieldRepo.findByTemplateId(templateId).stream()
            .anyMatch(f -> f.getTypeId().equals(typeId));
        if (alreadyPresent)
            throw new IllegalArgumentException("Field of this type already exists on this template");
        OffsetDateTime now = OffsetDateTime.now();
        FindingTemplateField field = new FindingTemplateField();
        field.setTemplateId(templateId);
        field.setTypeId(typeId);
        field.setFieldText(fieldText != null ? fieldText : "");
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        fieldRepo.save(field);
        t.setUpdatedAt(now);
        repo.save(t);
        return get(templateId);
    }

    @Transactional
    public FindingTemplateDto removeField(Long templateId, Long fieldId) {
        FindingTemplateField field = fieldRepo.findById(fieldId)
            .orElseThrow(() -> NotFoundException.of("field", fieldId));
        if (!field.getTemplateId().equals(templateId))
            throw NotFoundException.of("field", fieldId);
        boolean isRequired = fieldTypeRepo.findById(field.getTypeId()).map(FindingFieldType::isRequired).orElse(false);
        if (isRequired)
            throw new IllegalArgumentException("Required fields cannot be removed");
        fieldRepo.deleteById(fieldId);
        repo.findById(templateId).ifPresent(t -> { t.setUpdatedAt(OffsetDateTime.now()); repo.save(t); });
        return get(templateId);
    }

    @Transactional
    public FindingTemplateDto replaceScores(Long templateId, List<CreateFindingTemplateRequest.ScoreInput> scores) {
        FindingTemplate t = repo.findById(templateId).orElseThrow(() -> NotFoundException.of("finding_template", templateId));
        scoreRepo.deleteByTemplateId(templateId);
        OffsetDateTime now = OffsetDateTime.now();
        boolean hasDefault = scores.stream().anyMatch(s -> Boolean.TRUE.equals(s.isDefault()));
        int i = 0;
        for (var s : scores) {
            if (s.typeId() == null || s.score() == null) continue;
            FindingTemplateScore score = new FindingTemplateScore();
            score.setTemplateId(templateId);
            score.setTypeId(s.typeId());
            score.setScore(java.math.BigDecimal.valueOf(s.score()));
            score.setMetadata(s.metadata());
            score.setSsvcLeafNodeId(s.ssvcLeafNodeId());
            score.setDefault(hasDefault ? Boolean.TRUE.equals(s.isDefault()) : i == 0);
            score.setCreatedAt(now);
            score.setUpdatedAt(now);
            scoreRepo.save(score);
            i++;
        }
        t.setUpdatedAt(now);
        applyDerivedSeverity(t);
        repo.save(t);
        return get(templateId);
    }

    /**
     * Recomputes {@code t.severity} from whichever {@link FindingTemplateScore} is
     * currently marked default — null (no severity / "P?" placeholder client-side) if
     * the template has no scores or none is marked default.
     */
    private void applyDerivedSeverity(FindingTemplate t) {
        t.setSeverity(
            scoreRepo.findByTemplateId(t.getId()).stream()
                .filter(FindingTemplateScore::isDefault)
                .findFirst()
                .map(s -> com.martecyber.ares.common.SeverityThresholds.fromScore(s.getScore()))
                .orElse(null)
        );
    }

    @Transactional
    public FindingTemplateDto replaceReferences(Long templateId, List<Long> referenceIds) {
        FindingTemplate t = repo.findById(templateId)
            .orElseThrow(() -> NotFoundException.of("finding_template", templateId));
        // Remove template from all existing references
        referenceRepo.findByTemplateId(templateId).forEach(ref -> {
            ref.getFindingTemplates().remove(t);
            referenceRepo.save(ref);
        });
        // Add to new references
        if (referenceIds != null && !referenceIds.isEmpty()) {
            referenceRepo.findByIdIn(referenceIds).forEach(ref -> {
                ref.getFindingTemplates().add(t);
                referenceRepo.save(ref);
            });
        }
        t.setUpdatedAt(OffsetDateTime.now());
        repo.save(t);
        return get(templateId);
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("finding_template", id);
        repo.deleteById(id);
        workflowEventDispatcher.onFindingTemplateDeleted(id);
    }

    /** Best-effort: silently skips ids that no longer exist rather than failing the
     *  whole batch over one already-deleted row. Returns how many were actually removed. */
    @Transactional
    public int bulkDelete(List<Long> ids) {
        int count = 0;
        for (Long id : ids) {
            if (repo.existsById(id)) {
                repo.deleteById(id);
                workflowEventDispatcher.onFindingTemplateDeleted(id);
                count++;
            }
        }
        return count;
    }

    // ── Export / import ──────────────────────────────────────────────────────────
    // Export reuses CreateFindingTemplateRequest's own shape as the file format, so a
    // round-tripped export→import needs no separate DTO or mapping on the way back in.

    public CreateFindingTemplateRequest exportOne(Long id) {
        return toExportRequest(get(id));
    }

    private CreateFindingTemplateRequest toExportRequest(FindingTemplateDto dto) {
        var fields = dto.fields().stream()
            .map(f -> new CreateFindingTemplateRequest.FieldInput(f.typeId(), f.fieldText()))
            .toList();
        var scores = dto.scores().stream()
            .map(s -> new CreateFindingTemplateRequest.ScoreInput(
                s.typeId(), s.score() != null ? s.score().doubleValue() : null,
                s.metadata(), s.isDefault(), s.ssvcLeafNodeId()))
            .toList();
        var referenceIds = dto.references().stream().map(FindingTemplateDto.ReferenceDto::id).toList();
        return new CreateFindingTemplateRequest(dto.severity(), dto.title(), fields, scores, referenceIds);
    }

    /** A .json file per template, zipped together — used for the "export selected" bulk
     *  action. Zip entry names are best-effort human-readable (sanitized title + id) and
     *  otherwise carry no meaning; import re-parses each entry purely by content. */
    public byte[] exportZip(List<Long> ids) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(baos)) {
            for (Long id : ids) {
                CreateFindingTemplateRequest data = exportOne(id);
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

    /** Accepts any mix of .json files (one template each) and .zip files (a batch of
     *  .json entries, as produced by exportZip) in a single upload. Best-effort: one bad
     *  file or entry is reported in the result rather than aborting the whole import. */
    @Transactional
    public FindingTemplateImportResult importFiles(List<org.springframework.web.multipart.MultipartFile> files) {
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
                                create(MAPPER.readValue(bytes, CreateFindingTemplateRequest.class));
                                imported++;
                            } catch (Exception e) {
                                errors.add(name + "/" + entry.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                } else {
                    create(MAPPER.readValue(file.getBytes(), CreateFindingTemplateRequest.class));
                    imported++;
                }
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return new FindingTemplateImportResult(imported, errors);
    }

    /**
     * Snapshot a live finding into a new template. Copies title, severity, fields,
     * scores (default scores only) and references. Optional title override via the
     * request body; falls back to the finding's title.
     */
    @Transactional
    public FindingTemplateDto createFromFinding(Long findingId, FromFindingRequest req) {
        return createFromFinding(findingId, req, false);
    }

    /**
     * @param overwrite when {@code false} (the default via the single-arg overload above) and a
     *      template already has this exact title, throws {@link com.martecyber.ares.common.DuplicateNameException}
     *      instead of creating a second one — repeatedly "save as template"-ing edits of the same
     *      finding under the same name used to silently pile up near-duplicate templates. When
     *      {@code true}, that existing template's fields/scores are replaced in place (same row
     *      id, so its tags and KB references — both keyed on finding_template_id — survive
     *      untouched) rather than creating a new row.
     */
    @Transactional
    public FindingTemplateDto createFromFinding(Long findingId, FromFindingRequest req, boolean overwrite) {
        Finding f = findingRepo.findById(findingId)
            .orElseThrow(() -> NotFoundException.of("finding", findingId));
        Long creatorId = currentUserId();
        OffsetDateTime now = OffsetDateTime.now();

        String title = (req != null && req.title() != null && !req.title().isBlank())
            ? req.title().trim() : f.getTitle();

        FindingTemplate t;
        var existing = repo.findByTitleIgnoreCase(title);
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new com.martecyber.ares.common.DuplicateNameException(
                    "A finding template named \"" + title + "\" already exists");
            }
            t = existing.get();
            fieldRepo.deleteByTemplateId(t.getId());
            scoreRepo.deleteByTemplateId(t.getId());
        } else {
            t = new FindingTemplate();
            t.setCreatorId(creatorId);
            t.setCreatedAt(now);
        }
        t.setSeverity(f.getSeverity());
        t.setTitle(title);
        t.setUpdatedAt(now);
        FindingTemplate saved = repo.save(t);

        Map<String, FindingFieldType> typesByName = fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .collect(Collectors.toMap(FindingFieldType::getName, ty -> ty));
        for (var entry : com.martecyber.ares.findings.FindingFields.read(f).entrySet()) {
            FindingFieldType type = typesByName.get(entry.getKey());
            if (type == null) continue;
            FindingTemplateField field = new FindingTemplateField();
            field.setTemplateId(saved.getId());
            field.setTypeId(type.getId());
            field.setFieldText(entry.getValue());
            field.setCreatedAt(now);
            field.setUpdatedAt(now);
            fieldRepo.save(field);
        }

        // Default scores carry the finding's "canonical" CVSS values — those are the
        // ones useful to seed a template. Per-occurrence overrides aren't portable.
        for (var fs : findingScoreRepo.findByFindingId(findingId)) {
            if (!fs.isDefault()) continue;
            FindingTemplateScore score = new FindingTemplateScore();
            score.setTemplateId(saved.getId());
            score.setTypeId(fs.getTypeId());
            score.setScore(fs.getScore());
            score.setMetadata(fs.getVector());
            score.setSsvcLeafNodeId(fs.getSsvcLeafNodeId());
            score.setDefault(true); // the finding's canonical score stays the template's default
            score.setCreatedAt(now);
            score.setUpdatedAt(now);
            scoreRepo.save(score);
        }

        List<ReferenceEntry> refs = referenceRepo.findByFindingId(findingId);
        for (ReferenceEntry ref : refs) {
            ref.getFindingTemplates().add(saved);
            referenceRepo.save(ref);
        }

        workflowEventDispatcher.onFindingTemplateCreated(saved);
        return get(saved.getId());
    }

    public record FromFindingRequest(String title) {}

    private static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private List<FindingTemplateDto.ReferenceDto> buildReferenceDtos(List<ReferenceEntry> refs) {
        if (refs.isEmpty()) return List.of();
        Map<Long, String> catalogCodes = catalogRepo.findAll().stream()
            .collect(Collectors.toMap(c -> c.getId(), c -> c.getCode()));
        return refs.stream()
            .map(r -> new FindingTemplateDto.ReferenceDto(r.getId(), r.getCatalogId(),
                catalogCodes.getOrDefault(r.getCatalogId(), "?"), r.getTitle(), r.getDescription(),
                r.getUrl(), r.getFaviconUrl()))
            .toList();
    }

    private static FindingTemplateDto toDto(FindingTemplate t,
                                             List<FindingTemplateDto.FieldDto> fields,
                                             List<FindingTemplateDto.ScoreDto> scores,
                                             List<FindingTemplateDto.ReferenceDto> references) {
        return new FindingTemplateDto(t.getId(), t.getSeverity(), t.getTitle(), t.getCreatorId(),
            fields, scores, references, t.getCreatedAt(), t.getUpdatedAt(), List.of());
    }
}
