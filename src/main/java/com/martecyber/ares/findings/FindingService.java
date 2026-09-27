package com.martecyber.ares.findings;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectType;
import com.martecyber.ares.projects.ProjectTypeRepository;
import com.martecyber.ares.findings.dto.*;
import com.martecyber.ares.findings.templates.*;
import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionAffectsLinkRepository;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.aql.materialize.KbMaterializationService;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FindingService {

    private final FindingRepository repo;
    private final FindingStatusRepository statusRepo;
    private final FindingStatusTransitionRepository transitionRepo;
    private final FindingStatusHistoryRepository historyRepo;
    private final FindingFieldTypeRepository fieldTypeRepo;
    private final FindingScoreRepository scoreRepo;
    private final FindingScoreTypeRepository scoreTypeRepo;
    private final ProjectRepository projectRepo;
    private final ProjectTypeRepository projectTypeRepo;
    private final FindingTemplateRepository templateRepo;
    private final FindingTemplateFieldRepository templateFieldRepo;
    private final FindingTemplateScoreRepository templateScoreRepo;
    private final AffectionRepository affectionRepo;
    private final AffectionAffectsLinkRepository affectsLinkRepo;
    private final AssetRepository assetRepo;
    private final ReferenceEntryRepository referenceEntryRepo;
    private final ReferenceCatalogRepository referenceCatalogRepo;
    private final KbMaterializationService materializationService;
    private final OrgScopeService orgScope;
    private final FindingAqlRegistry aqlRegistry;
    private final com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final FindingTagRepository tagRepo;
    private final com.martecyber.ares.tags.TagRepository tagCatalogRepo;

    public FindingService(
        FindingRepository repo,
        FindingStatusRepository statusRepo,
        FindingStatusTransitionRepository transitionRepo,
        FindingStatusHistoryRepository historyRepo,
        FindingFieldTypeRepository fieldTypeRepo,
        FindingScoreRepository scoreRepo,
        FindingScoreTypeRepository scoreTypeRepo,
        ProjectRepository projectRepo,
        ProjectTypeRepository projectTypeRepo,
        FindingTemplateRepository templateRepo,
        FindingTemplateFieldRepository templateFieldRepo,
        FindingTemplateScoreRepository templateScoreRepo,
        AffectionRepository affectionRepo,
        AffectionAffectsLinkRepository affectsLinkRepo,
        AssetRepository assetRepo,
        ReferenceEntryRepository referenceEntryRepo,
        ReferenceCatalogRepository referenceCatalogRepo,
        KbMaterializationService materializationService,
        OrgScopeService orgScope,
        FindingAqlRegistry aqlRegistry,
        WorkflowEventDispatcher workflowEventDispatcher,
        FindingTagRepository tagRepo,
        com.martecyber.ares.tags.TagRepository tagCatalogRepo,
        com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander
    ) {
        this.aqlVariableExpander = aqlVariableExpander;
        this.repo = repo;
        this.aqlRegistry = aqlRegistry;
        this.workflowEventDispatcher = workflowEventDispatcher;
        this.statusRepo = statusRepo;
        this.transitionRepo = transitionRepo;
        this.historyRepo = historyRepo;
        this.fieldTypeRepo = fieldTypeRepo;
        this.scoreRepo = scoreRepo;
        this.scoreTypeRepo = scoreTypeRepo;
        this.projectRepo = projectRepo;
        this.projectTypeRepo = projectTypeRepo;
        this.templateRepo = templateRepo;
        this.templateFieldRepo = templateFieldRepo;
        this.templateScoreRepo = templateScoreRepo;
        this.affectionRepo = affectionRepo;
        this.affectsLinkRepo = affectsLinkRepo;
        this.assetRepo = assetRepo;
        this.referenceEntryRepo = referenceEntryRepo;
        this.referenceCatalogRepo = referenceCatalogRepo;
        this.materializationService = materializationService;
        this.orgScope = orgScope;
        this.tagRepo = tagRepo;
        this.tagCatalogRepo = tagCatalogRepo;
    }

    /** Assigns an existing tag to a finding — a platform tag (no organization of its own) or a
     *  tag belonging to the finding's own organization. */
    @Transactional
    public FindingDto assignTag(Long findingId, Long projectId, Long tagId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        Long orgId = projectRepo.findById(f.getProjectId())
            .orElseThrow(() -> NotFoundException.of("project", f.getProjectId())).getOrganizationId();
        var tag = tagCatalogRepo.findById(tagId).orElseThrow(() -> NotFoundException.of("tag", tagId));
        if (tag.getOrganizationId() != null && !tag.getOrganizationId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag belongs to a different organization");
        }
        tagRepo.assign(findingId, tagId);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto unassignTag(Long findingId, Long projectId, Long tagId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        tagRepo.unassign(findingId, tagId);
        return buildFullDto(f);
    }

    /** Mirrors DetectionReferenceExtractor's own materialize-on-link call — a CVE reference
     *  attached to a Finding needs the same kb_materialized_ref fast-path row a Detection-linked
     *  one gets, or FindingAqlRegistry's cve.kevListed/cvssScore/severity/exploitCount would
     *  silently never match findings whose only linked detection-side reference doesn't exist. */
    private void materializeIfCve(ReferenceEntry ref) {
        referenceCatalogRepo.findById(ref.getCatalogId())
            .filter(c -> "CVE".equals(c.getCode()))
            .ifPresent(c -> materializationService.materializeIfAbsent(ref.getTitle()));
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static Long currentUserId() {
        var auth = currentAuth();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    /** True for both client tiers (CLIENT_USER and CLIENT_ADMIN) — neither can ever see drafts. */
    private static boolean isClientUser(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_CLIENT_USER".equals(a.getAuthority()) || "ROLE_CLIENT_ADMIN".equals(a.getAuthority()));
    }

    /**
     * A finding's own content (title, description, severity, scores, references, custom
     * fields, publish state) can only be edited from the project it actually belongs to —
     * never from a RETEST project that merely has it in scope for verification. Callers
     * pass the project id they believe they're operating in; this rejects mismatches
     * server-side rather than relying on the UI not exposing edit controls in retest mode.
     */
    private void requireOwnProject(Finding f, Long projectId) {
        if (projectId == null || !projectId.equals(f.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Finding " + f.getId() + " can only be edited from its own project");
        }
        orgScope.assertProjectAccess(currentAuth(), projectId);
    }

    /** True when {@code typeId} is the RETEST master or any user-defined subtype of it. */
    private boolean isRetestType(Long typeId) {
        if (typeId == null) return false;
        return projectTypeRepo.findById(typeId).map(this::isRetestType).orElse(false);
    }

    private boolean isRetestType(ProjectType type) {
        if ("RETEST".equals(type.getCode())) return true;
        if (type.getSupertypeId() == null) return false;
        return projectTypeRepo.findById(type.getSupertypeId())
            .map(t -> "RETEST".equals(t.getCode()))
            .orElse(false);
    }

    // ── Custom fields (finding.fields jsonb, keyed by finding_field_type.name) ────────────────
    // Replaces the old finding_field EAV table (AQL implementation plan, V141 hard cutover).
    // finding_field_type remains the live catalog for field *types* — only where a finding's own
    // field *values* live has changed, not how types are declared/managed. Read/write logic
    // lives in FindingFields, shared with FindingTemplateService/ReportGenerationService/
    // MarkdownMigrationService — every other former FindingFieldRepository call site.

    /** Ordered by sortOrder/title, matching the old finding_field query's ORDER BY. */
    private List<FindingDto.FieldDto> fieldDtosFor(Finding f) {
        Map<String, String> values = FindingFields.read(f);
        return fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .filter(t -> values.containsKey(t.getName()))
            .map(t -> new FindingDto.FieldDto(t.getId(), t.getId(), t.getTitle(),
                values.get(t.getName()), f.getCreatedAt(), f.getUpdatedAt()))
            .toList();
    }

    public Page<FindingDto> list(Long projectId, List<Long> projectIds, Long orgId, boolean includeDrafts, List<String> severities, List<Long> statusIds, String iterationLabel, String q, int page, int size) {
        var auth = currentAuth();
        if (isClientUser(auth)) includeDrafts = false;

        java.util.Collection<Long> orgIds = resolveFindingOrgScope(projectId, orgId);
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        if (orgIds != null && orgIds.isEmpty()) return Page.empty(p);

        String cleanQ = q != null && !q.isBlank() ? q.trim() : null;
        String qLike = cleanQ != null ? "%" + cleanQ.toLowerCase() + "%" : null;
        var results = repo.filter(projectId, projectIds, orgId, orgIds, includeDrafts, severities, statusIds, iterationLabel, qLike, p);
        return mapToDto(results);
    }

    /**
     * AQL-driven listing (AQL implementation plan, Phase 3) — coexists with {@link #list} rather
     * than replacing it; when a caller supplies {@code aql} the API layer ignores the discrete
     * filter params entirely, same coexistence rule as Detection's.
     */
    @Transactional
    public Page<FindingDto> listByAql(Long projectId, Long orgId, boolean includeDrafts, String aql,
                                       String sortBy, String sortDir, int page, int size) {
        var auth = currentAuth();
        if (isClientUser(auth)) includeDrafts = false;

        java.util.Collection<Long> orgIds = resolveFindingOrgScope(projectId, orgId);
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), buildFindingSort(sortBy, sortDir));
        if (orgIds != null && orgIds.isEmpty()) return Page.empty(p);

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, orgId));
        org.springframework.data.jpa.domain.Specification<Finding> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgId, orgIds, includeDrafts));

        return mapToDto(repo.findAll(spec, p));
    }

    /** Count-only variant of {@link #listByAql} — same scope resolution and AQL compilation, no
     *  paging/sort/DTO-mapping, for Workflow CONDITION nodes comparing result counts instead of
     *  fetching rows. */
    @Transactional
    public long countByAql(Long projectId, Long orgId, boolean includeDrafts, String aql) {
        var auth = currentAuth();
        if (isClientUser(auth)) includeDrafts = false;

        java.util.Collection<Long> orgIds = resolveFindingOrgScope(projectId, orgId);
        if (orgIds != null && orgIds.isEmpty()) return 0;

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, orgId));
        org.springframework.data.jpa.domain.Specification<Finding> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgId, orgIds, includeDrafts));
        return repo.count(spec);
    }

    /** Grouped-count variant of {@link #countByAql} for the dashboard AQL_CHART widget — same
     *  scope resolution and AQL compilation, grouped by {@code groupByField} instead of a single
     *  total. See {@link com.martecyber.ares.aql.compile.AqlGroupCountSupport}.
     *
     *  <p>{@code dateBucket == "iteration"} is handled entirely here rather than passed into
     *  {@code AqlGroupCountSupport} — {@code iterationLabel} is a plain groupable STRING field
     *  (see {@link FindingAqlRegistry}) whose label format (`YY-MM`, `YY-Www`, `YY-Qq`, ...)
     *  already sorts correctly as a string, so "bucket by iteration" is really just "group by
     *  iterationLabel, sorted chronologically" — no {@code date_trunc} involved, and
     *  {@code groupByField} is ignored (the axis is implicit). */
    @Transactional
    public List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> countGroupedByAql(
            Long projectId, Long orgId, boolean includeDrafts, String aql, String groupByField, String dateBucket,
            String seriesField, Integer topN, String sortMode) {
        var auth = currentAuth();
        if (isClientUser(auth)) includeDrafts = false;

        java.util.Collection<Long> orgIds = resolveFindingOrgScope(projectId, orgId);
        if (orgIds != null && orgIds.isEmpty()) return List.of();

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, orgId));
        org.springframework.data.jpa.domain.Specification<Finding> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgId, orgIds, includeDrafts));
        if ("iteration".equals(dateBucket)) {
            return com.martecyber.ares.aql.compile.AqlGroupCountSupport.execute(
                em, spec, Finding.class, aqlRegistry, "iterationLabel", null, seriesField, topN, "label_asc", true);
        }
        return com.martecyber.ares.aql.compile.AqlGroupCountSupport.execute(
            em, spec, Finding.class, aqlRegistry, groupByField, dateBucket, seriesField, topN, sortMode);
    }

    /** Mirrors list()'s access checks: a specific project or org requires explicit access;
     *  otherwise non-admins are restricted to their accessible orgs. Returns null when the
     *  caller isn't limited to a specific org set. */
    private java.util.Collection<Long> resolveFindingOrgScope(Long projectId, Long orgId) {
        var auth = currentAuth();
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
            return null;
        }
        if (orgId != null) {
            orgScope.assertOrgAccess(auth, orgId);
            return null;
        }
        if (!orgScope.isPlatformAdmin(auth)) {
            return orgScope.accessibleOrgIds(auth);
        }
        return null;
    }

    /** Theta-join scope predicate mirroring FindingRepository.filter's JOIN Project p ON p.id =
     *  f.projectId — Finding has no mapped association to Project, only a bare projectId column. */
    private org.springframework.data.jpa.domain.Specification<Finding> scopeSpecification(
            Long projectId, Long orgId, java.util.Collection<Long> orgIds, boolean includeDrafts) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            } else if (orgId != null || orgIds != null) {
                var projectRoot = query.from(com.martecyber.ares.projects.Project.class);
                predicates.add(cb.equal(projectRoot.get("id"), root.get("projectId")));
                predicates.add(orgId != null
                    ? cb.equal(projectRoot.get("organizationId"), orgId)
                    : projectRoot.get("organizationId").in(orgIds));
            }
            if (!includeDrafts) {
                predicates.add(cb.equal(root.get("isDraft"), false));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private org.springframework.data.domain.Sort buildFindingSort(String sortBy, String sortDir) {
        var dir = "asc".equalsIgnoreCase(sortDir)
            ? org.springframework.data.domain.Sort.Direction.ASC
            : org.springframework.data.domain.Sort.Direction.DESC;
        String col = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "title" -> "title";
            case "priority" -> "priority";
            case "duedate" -> "dueDate";
            case "sladeadline" -> "slaDeadline";
            case "reportedat" -> "reportedAt";
            case "updatedat" -> "updatedAt";
            default -> "createdAt";
        };
        return org.springframework.data.domain.Sort.by(dir, col);
    }

    private Page<FindingDto> mapToDto(Page<Finding> results) {
        Map<Long, String> statusNames = loadStatusNames();

        // Batch-compute remediation status for published findings (2 queries for the page, not N+1)
        List<Long> publishedIds = results.getContent().stream()
            .filter(f -> !f.isDraft()).map(Finding::getId).toList();
        Map<Long, String> remediationStatuses = new java.util.HashMap<>();
        if (!publishedIds.isEmpty()) {
            Set<Long> withAffections = new java.util.HashSet<>(affectionRepo.findFindingIdsWithAffections(publishedIds));
            Set<Long> openIds       = new java.util.HashSet<>(affectionRepo.findFindingIdsWithOpenAffections(publishedIds));
            for (Long id : publishedIds) {
                remediationStatuses.put(id, (withAffections.contains(id) && !openIds.contains(id)) ? "closed" : "open");
            }
        }
        Map<Long, List<com.martecyber.ares.tags.TagDto>> tagsByFinding = loadTagsByFindingIds(
            results.getContent().stream().map(Finding::getId).toList());
        return results.map(f -> toDtoShort(f, statusNames, f.isDraft() ? null : remediationStatuses.get(f.getId()))
            .withTags(tagsByFinding.getOrDefault(f.getId(), List.of())));
    }

    private Map<Long, List<com.martecyber.ares.tags.TagDto>> loadTagsByFindingIds(List<Long> findingIds) {
        if (findingIds.isEmpty()) return Map.of();
        Map<Long, List<com.martecyber.ares.tags.TagDto>> byFinding = new java.util.HashMap<>();
        for (FindingTagRepository.FindingTagRow row : tagRepo.findTagsForFindingIds(findingIds)) {
            byFinding.computeIfAbsent(row.getFindingId(), k -> new java.util.ArrayList<>())
                .add(new com.martecyber.ares.tags.TagDto(row.getId(), null, row.getName(), row.getColor()));
        }
        return byFinding;
    }

    public List<FindingDto> listByAsset(Long assetId, Long projectId, Long orgId) {
        var auth = currentAuth();
        if (projectId != null) orgScope.assertProjectAccess(auth, projectId);
        else if (orgId != null) orgScope.assertOrgAccess(auth, orgId);
        List<Finding> findings = projectId != null
            ? repo.findByAssetAndProject(assetId, projectId)
            : repo.findByAssetAndOrg(assetId, orgId != null ? orgId : 0L);
        Map<Long, String> statusNames = loadStatusNames();
        List<Long> ids = findings.stream().map(Finding::getId).toList();
        Set<Long> withAffections = ids.isEmpty() ? Set.of() : new java.util.HashSet<>(affectionRepo.findFindingIdsWithAffections(ids));
        Set<Long> openIds        = ids.isEmpty() ? Set.of() : new java.util.HashSet<>(affectionRepo.findFindingIdsWithOpenAffections(ids));
        return findings.stream().map(f -> {
            String rem = (withAffections.contains(f.getId()) && !openIds.contains(f.getId())) ? "closed" : "open";
            return toDtoShort(f, statusNames, rem);
        }).toList();
    }

    @Transactional
    public FindingDto get(Long id) {
        Finding f = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding", id));
        var auth = currentAuth();
        orgScope.assertProjectAccess(auth, f.getProjectId());
        if (isClientUser(auth) && f.isDraft()) {
            throw NotFoundException.of("finding", id);
        }
        return buildFullDto(f);
    }

    public List<FindingFieldTypeDto> listFieldTypes() {
        return fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .map(FindingService::toFieldTypeDto)
            .toList();
    }

    public List<FindingScoreTypeDto> listScoreTypes() {
        return scoreTypeRepo.findAll().stream()
            .map(t -> new FindingScoreTypeDto(t.getId(), t.getTitle(), t.getDescription()))
            .toList();
    }

    public List<FindingStatusDto> listStatuses() {
        return statusRepo.findAll().stream()
            .map(s -> new FindingStatusDto(s.getId(), s.getName(), s.getDescription(), s.isMeansClosed()))
            .toList();
    }

    @Transactional
    public FindingDto create(CreateFindingRequest req) {
        var project = projectRepo.findById(req.projectId())
            .orElseThrow(() -> NotFoundException.of("project", req.projectId()));

        if (isRetestType(project.getTypeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Retesting projects cannot create new findings — link existing ones to the project instead");
        }

        // Findings always start as drafts in 'drafting' status
        FindingStatus status = statusRepo.findByName("drafting")
            .orElseThrow(() -> new IllegalStateException("Finding status 'drafting' not found"));

        OffsetDateTime now = OffsetDateTime.now();
        String engCode = project.getCode() != null ? project.getCode() : String.valueOf(req.projectId());

        Finding f = new Finding();
        f.setProjectId(req.projectId());
        f.setTitle(req.title().trim());
        // Severity is derived from the default score once scores are attached below —
        // not settable independently. No default score yet => no severity (shown as the
        // "P?" unknown-priority placeholder client-side) rather than a guessed fallback.
        f.setSeverity(null);
        f.setStatusId(status.getId());
        f.setCreatorId(currentUserId());
        f.setDraft(true);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);

        // Assign temp code immediately: {ENG_CODE}[-{iterLabel}]-TEMP-{n}
        String draftIterLabel = com.martecyber.ares.projects.MonitorIterationHelper
            .computeLabel(project.getIterationCadence(), java.time.LocalDate.now());
        long tempSeq = repo.countByProjectId(req.projectId()) + 1;
        f.setCode(draftIterLabel != null
            ? engCode + "-" + draftIterLabel + "-TEMP-" + tempSeq
            : engCode + "-TEMP-" + tempSeq);

        // ── Fields ────────────────────────────────────────────────────────────
        // At most one field per type per finding — explicit request fields win (the create
        // form already flattens the selected template's fields into req.fields() so the user
        // can edit them before submitting); the template is only consulted to fill in any
        // type it defines that the request didn't already cover, never to duplicate one it did.
        // putIfAbsent (rather than a separate "covered types" set) gives first-occurrence-wins
        // for all three sources in one pass: request fields, then template, then required
        // defaults — no separate dedup bookkeeping needed against a jsonb map.
        Map<String, String> fields = new LinkedHashMap<>();
        Map<Long, FindingFieldType> typesById = fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .collect(Collectors.toMap(FindingFieldType::getId, t -> t));
        if (req.fields() != null) {
            req.fields().forEach(fr -> {
                FindingFieldType type = typesById.get(fr.typeId());
                if (type != null) fields.putIfAbsent(type.getName(), fr.fieldText());
            });
        }
        if (req.templateId() != null) {
            templateFieldRepo.findByTemplateId(req.templateId()).forEach(tf -> {
                FindingFieldType type = typesById.get(tf.getTypeId());
                if (type != null) fields.putIfAbsent(type.getName(), tf.getFieldText());
            });
        }
        fieldTypeRepo.findByRequiredTrueOrderBySortOrderAsc()
            .forEach(ft -> fields.putIfAbsent(ft.getName(), ""));
        FindingFields.write(f, fields);

        repo.save(f);
        historyRepo.save(new FindingStatusHistory(f.getId(), status.getId(), now));

        // ── Scores ────────────────────────────────────────────────────────────
        if (req.scores() != null && !req.scores().isEmpty()) {
            boolean hasDefault = req.scores().stream().anyMatch(s -> Boolean.TRUE.equals(s.isDefault()));
            List<FindingScore> scoresToSave = new ArrayList<>();
            for (int i = 0; i < req.scores().size(); i++) {
                var sr = req.scores().get(i);
                FindingScore fs = new FindingScore();
                fs.setFindingId(f.getId());
                fs.setTypeId(sr.typeId());
                fs.setScore(sr.score());
                fs.setVector(sr.vector());
                fs.setSsvcLeafNodeId(sr.ssvcLeafNodeId());
                fs.setDefault(hasDefault ? Boolean.TRUE.equals(sr.isDefault()) : i == 0);
                fs.setCreatedAt(now);
                fs.setUpdatedAt(now);
                scoresToSave.add(fs);
            }
            scoreRepo.saveAll(scoresToSave);
        } else if (req.templateId() != null) {
            // Copy template scores, preserving which one the template had as default
            // so the new finding's severity derives the same way the template's did.
            List<FindingScore> tplScores = new ArrayList<>();
            templateScoreRepo.findByTemplateId(req.templateId()).forEach(ts -> {
                FindingScore fs = new FindingScore();
                fs.setFindingId(f.getId());
                fs.setTypeId(ts.getTypeId());
                fs.setScore(ts.getScore());
                fs.setVector(ts.getMetadata());
                fs.setSsvcLeafNodeId(ts.getSsvcLeafNodeId());
                fs.setDefault(ts.isDefault());
                fs.setCreatedAt(now);
                fs.setUpdatedAt(now);
                tplScores.add(fs);
            });
            scoreRepo.saveAll(tplScores);
        }

        applyDerivedSeverity(f);
        repo.save(f);

        // ── References ────────────────────────────────────────────────────────
        // Explicit referenceIds take priority; if none provided and a template was used,
        // copy the template's references automatically.
        List<Long> refIds = req.referenceIds();
        if ((refIds == null || refIds.isEmpty()) && req.templateId() != null) {
            refIds = referenceEntryRepo.findByTemplateId(req.templateId()).stream()
                .map(ReferenceEntry::getId).toList();
        }
        if (refIds != null && !refIds.isEmpty()) {
            List<ReferenceEntry> refs = referenceEntryRepo.findByIdIn(refIds);
            refs.forEach(ref -> { ref.getFindings().add(f); materializeIfCve(ref); });
            referenceEntryRepo.saveAll(refs);
        }

        // ── Affection ─────────────────────────────────────────────────────────
        // Optional: a null affection leaves the finding with none yet — the escalation
        // wizard creates the finding first (fields/scores/references only) and adds the
        // affection as its own step via addAffection(), same call this block used to make inline.
        if (req.affection() != null) {
            Affection aff = new Affection();
            aff.setFindingId(f.getId());
            aff.setCode(generateAffectionCode(f.getId(), f.getCode() != null ? f.getCode() : "F" + f.getId()));
            if (req.affection().title() != null) aff.setTitle(req.affection().title());
            if (req.affection().description() != null) aff.setDescription(req.affection().description());
            aff.setCreatedAt(now);
            aff.setUpdatedAt(now);
            affectionRepo.save(aff); // flush to get generated ID before adding asset links
            boolean affDirty = false;
            if (req.affection().detectedAtIds() != null && !req.affection().detectedAtIds().isEmpty()) {
                assetRepo.findAllById(req.affection().detectedAtIds())
                    .forEach(a -> aff.addAssetLink(a.getId(), "detected_at", now));
                affDirty = true;
            }
            if (req.affection().affectsIds() != null && !req.affection().affectsIds().isEmpty()) {
                assetRepo.findAllById(req.affection().affectsIds())
                    .forEach(a -> aff.addAssetLink(a.getId(), "affects", null));
                affDirty = true;
            }
            if (affDirty) affectionRepo.save(aff);
        }

        workflowEventDispatcher.onFindingCreated(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto update(Long id, Long projectId, UpdateFindingRequest req) {
        Finding f = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding", id));
        requireOwnProject(f, projectId);

        if (req.title() != null && !req.title().isBlank()) f.setTitle(req.title().trim());
        // Severity is no longer independently settable — it's derived from whichever
        // score is marked default (see applyDerivedSeverity / addScore / updateScore /
        // removeScore). req.severity() is intentionally ignored here.
        if (Boolean.TRUE.equals(req.clearDueDate())) f.setDueDate(null);
        else if (req.dueDate() != null) f.setDueDate(req.dueDate());

        if (req.statusId() != null && !req.statusId().equals(f.getStatusId())) {
            FindingStatus newStatus = statusRepo.findById(req.statusId())
                .orElseThrow(() -> NotFoundException.of("finding_status", req.statusId()));
            if (!transitionRepo.existsById(new FindingStatusTransitionId(f.getStatusId(), req.statusId()))) {
                throw new com.martecyber.ares.common.ConflictException(
                    "No valid transition from status " + f.getStatusId() + " to " + req.statusId());
            }
            f.setStatusId(req.statusId());
            historyRepo.save(new FindingStatusHistory(f.getId(), req.statusId(), OffsetDateTime.now()));
            // "resolved" is the specific terminal status this timestamp tracks — distinct
            // from "accepted_risk"/"false_positive", which also close the finding but
            // aren't a resolution. Cleared on any transition away from it (reopen).
            f.setResolvedAt("resolved".equals(newStatus.getName()) ? OffsetDateTime.now() : null);
        }

        f.setUpdatedAt(OffsetDateTime.now());
        repo.save(f);
        workflowEventDispatcher.onFindingUpdated(f);
        return buildFullDto(f);
    }

    @Transactional
    public void delete(Long id, Long projectId) {
        Finding f = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding", id));
        requireOwnProject(f, projectId);
        repo.deleteById(id);
        workflowEventDispatcher.onFindingDeleted(id, projectId);
    }

    @Transactional
    public FindingDto addAffection(Long findingId, Long projectId, CreateFindingRequest.AffectionRequest req) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        OffsetDateTime now = OffsetDateTime.now();
        Affection aff = new Affection();
        aff.setFindingId(f.getId());
        aff.setCode(generateAffectionCode(f.getId(), f.getCode() != null ? f.getCode() : "F" + f.getId()));
        if (req.title() != null) aff.setTitle(req.title());
        if (req.description() != null) aff.setDescription(req.description());
        aff.setCreatedAt(now);
        aff.setUpdatedAt(now);
        affectionRepo.save(aff);
        boolean dirty = false;
        if (req.detectedAtIds() != null && !req.detectedAtIds().isEmpty()) {
            assetRepo.findAllById(req.detectedAtIds())
                .forEach(a -> aff.addAssetLink(a.getId(), "detected_at", now));
            dirty = true;
        }
        if (req.affectsIds() != null && !req.affectsIds().isEmpty()) {
            assetRepo.findAllById(req.affectsIds())
                .forEach(a -> aff.addAssetLink(a.getId(), "affects", null));
            dirty = true;
        }
        if (dirty) affectionRepo.save(aff);
        f.setUpdatedAt(now);
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto publish(Long id, Long projectId) {
        Finding f = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding", id));
        requireOwnProject(f, projectId);
        if (!f.isDraft()) return buildFullDto(f);
        return doPublish(f, repo.countPublishedByProjectId(f.getProjectId()) + 1);
    }

    @Transactional
    public List<FindingDto> publishBatch(Long projectId, List<Long> ids) {
        if (!projectRepo.existsById(projectId)) throw NotFoundException.of("project", projectId);
        List<Finding> findings = repo.findDraftsByIdsOrderBySeverity(ids);
        long nextSeq = repo.countPublishedByProjectId(projectId) + 1;
        List<FindingDto> result = new ArrayList<>();
        for (Finding f : findings) {
            result.add(doPublish(f, nextSeq++));
        }
        return result;
    }

    @Transactional
    public List<FindingDto> publishAllReady(Long projectId) {
        if (!projectRepo.existsById(projectId)) throw NotFoundException.of("project", projectId);
        FindingStatus readyStatus = statusRepo.findByName("ready_to_publish")
            .orElseThrow(() -> new IllegalStateException("Status 'ready_to_publish' not found"));
        List<Finding> findings = repo.findReadyToPublishOrderBySeverity(projectId, readyStatus.getId());
        long nextSeq = repo.countPublishedByProjectId(projectId) + 1;
        List<FindingDto> result = new ArrayList<>();
        for (Finding f : findings) {
            result.add(doPublish(f, nextSeq++));
        }
        return result;
    }

    private FindingDto doPublish(Finding f, long seq) {
        var project = projectRepo.findById(f.getProjectId())
            .orElseThrow(() -> NotFoundException.of("project", f.getProjectId()));
        String engCode = project.getCode() != null ? project.getCode() : String.valueOf(f.getProjectId());
        FindingStatus openStatus = statusRepo.findByName("open")
            .orElseThrow(() -> new IllegalStateException("Finding status 'open' not found"));

        OffsetDateTime now = OffsetDateTime.now();

        // MONITOR projects: embed the iteration label in the finding code and store it separately.
        // Resolves the active/approved iteration, not just whatever the calendar currently says.
        String iterLabel = com.martecyber.ares.projects.MonitorIterationHelper
            .resolveActiveLabel(project, projectRepo);
        if (iterLabel != null) {
            long iterSeq = repo.countPublishedByProjectAndLabel(f.getProjectId(), iterLabel) + 1;
            f.setCode(engCode + "-" + iterLabel + "-" + iterSeq);
            f.setIterationLabel(iterLabel);
        } else {
            f.setCode(engCode + "-" + seq);
        }

        f.setDraft(false);
        f.setStatusId(openStatus.getId());
        f.setReportedAt(now);   // capture publish timestamp for SLA calculation
        f.setUpdatedAt(now);
        repo.save(f);
        historyRepo.save(new FindingStatusHistory(f.getId(), openStatus.getId(), now));

        // Affection codes are derived from the finding code (e.g. {findingCode}-{n}); once the
        // finding's draft/temp code is replaced with its final published code, re-derive them too
        // so they don't stay stuck referencing the old temp prefix.
        List<Affection> affections = new ArrayList<>(affectionRepo.findByFindingIdWithAssets(f.getId()));
        affections.sort(Comparator.comparing(Affection::getCreatedAt).thenComparing(Affection::getId));
        long affSeq = 1;
        for (Affection aff : affections) {
            aff.setCode(f.getCode() + "-" + affSeq++);
            aff.setUpdatedAt(now);
            affectionRepo.save(aff);
        }

        return buildFullDto(f);
    }

    /**
     * {@code typeId} is finding_field_type.id — the FieldDto the client holds has no separate
     * row identity anymore now that values live in a jsonb map (there's nothing left to have one
     * beyond the type itself), so FieldDto.id and FieldDto.typeId are now the same value and
     * either can be passed back here.
     */
    @Transactional
    public FindingDto updateField(Long findingId, Long projectId, Long typeId, String fieldText) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        FindingFieldType type = fieldTypeRepo.findById(typeId)
            .orElseThrow(() -> NotFoundException.of("finding_field_type", typeId));
        Map<String, String> fields = FindingFields.read(f);
        if (!fields.containsKey(type.getName()))
            throw NotFoundException.of("finding_field", typeId);
        fields.put(type.getName(), fieldText != null ? fieldText : "");
        FindingFields.write(f, fields);
        f.setUpdatedAt(OffsetDateTime.now());
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto addField(Long findingId, Long projectId, Long typeId, String fieldText) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        FindingFieldType type = fieldTypeRepo.findById(typeId)
            .orElseThrow(() -> NotFoundException.of("finding_field_type", typeId));
        Map<String, String> fields = FindingFields.read(f);
        if (fields.containsKey(type.getName()))
            throw new IllegalArgumentException("Field of this type already exists on this finding");
        fields.put(type.getName(), fieldText != null ? fieldText : "");
        FindingFields.write(f, fields);
        f.setUpdatedAt(OffsetDateTime.now());
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto removeField(Long findingId, Long projectId, Long typeId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        FindingFieldType type = fieldTypeRepo.findById(typeId)
            .orElseThrow(() -> NotFoundException.of("finding_field_type", typeId));
        if (type.isRequired())
            throw new IllegalArgumentException("Required fields cannot be removed");
        Map<String, String> fields = FindingFields.read(f);
        if (!fields.containsKey(type.getName()))
            throw NotFoundException.of("finding_field", typeId);
        fields.remove(type.getName());
        FindingFields.write(f, fields);
        f.setUpdatedAt(OffsetDateTime.now());
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto updateScore(Long findingId, Long projectId, Long scoreId,
                                   java.math.BigDecimal score, String vector, Boolean isDefault, String comment,
                                   Long ssvcLeafNodeId, boolean ssvcLeafNodeIdProvided) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        FindingScore fs = scoreRepo.findById(scoreId)
            .orElseThrow(() -> NotFoundException.of("finding_score", scoreId));
        if (!fs.getFindingId().equals(findingId))
            throw new IllegalArgumentException("Score does not belong to this finding");
        if (score != null) fs.setScore(score);
        if (vector != null) fs.setVector(vector.isBlank() ? null : vector);
        if (comment != null) fs.setComment(comment);
        if (ssvcLeafNodeIdProvided) fs.setSsvcLeafNodeId(ssvcLeafNodeId);
        if (Boolean.TRUE.equals(isDefault) && !fs.isDefault()) {
            scoreRepo.findByFindingId(findingId).forEach(s -> {
                if (!s.getId().equals(scoreId) && s.isDefault()) {
                    s.setDefault(false);
                    scoreRepo.save(s);
                }
            });
            fs.setDefault(true);
        }
        fs.setUpdatedAt(OffsetDateTime.now());
        scoreRepo.save(fs);
        f.setUpdatedAt(OffsetDateTime.now());
        applyDerivedSeverity(f);
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto addScore(Long findingId, Long projectId, Long typeId, BigDecimal score, String vector, boolean isDefault, String comment, Long ssvcLeafNodeId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        OffsetDateTime now = OffsetDateTime.now();
        List<FindingScore> existing = scoreRepo.findByFindingId(findingId);
        // A finding's very first score is always its default, regardless of what the
        // caller passed — otherwise a finding could end up with scores but none marked
        // default, and therefore no derived severity, purely by client omission.
        boolean effectiveDefault = isDefault || existing.isEmpty();
        if (effectiveDefault) {
            existing.forEach(s -> {
                if (s.isDefault()) { s.setDefault(false); scoreRepo.save(s); }
            });
        }
        FindingScore fs = new FindingScore();
        fs.setFindingId(findingId);
        fs.setTypeId(typeId);
        fs.setScore(score);
        fs.setVector(vector != null && !vector.isBlank() ? vector : null);
        fs.setSsvcLeafNodeId(ssvcLeafNodeId);
        fs.setDefault(effectiveDefault);
        fs.setComment(comment);
        fs.setCreatedAt(now);
        fs.setUpdatedAt(now);
        scoreRepo.save(fs);
        f.setUpdatedAt(now);
        applyDerivedSeverity(f);
        repo.save(f);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto removeScore(Long findingId, Long projectId, Long scoreId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        FindingScore fs = scoreRepo.findById(scoreId)
            .orElseThrow(() -> NotFoundException.of("finding_score", scoreId));
        if (!fs.getFindingId().equals(findingId))
            throw new IllegalArgumentException("Score does not belong to this finding");
        boolean wasDefault = fs.isDefault();
        scoreRepo.deleteById(scoreId);
        if (wasDefault) {
            scoreRepo.findByFindingId(findingId).stream().findFirst().ifPresent(s -> {
                s.setDefault(true);
                scoreRepo.save(s);
            });
        }
        f.setUpdatedAt(OffsetDateTime.now());
        applyDerivedSeverity(f);
        repo.save(f);
        return buildFullDto(f);
    }

    /**
     * Recomputes {@code f.priority} (canonical, AQL implementation plan V144) and {@code
     * f.severity} (derived from it, kept as the display value) from whichever {@link
     * FindingScore} is currently marked default — both null (no priority / shown as the "P?"
     * placeholder) if none is. Called after every score mutation so neither can drift from the
     * score that's supposed to drive them. Priority and severity are computed from the exact
     * same lookup so they can never disagree with each other.
     */
    private void applyDerivedSeverity(Finding f) {
        Short priority = scoreRepo.findByFindingId(f.getId()).stream()
            .filter(FindingScore::isDefault)
            .findFirst()
            .map(s -> com.martecyber.ares.common.PriorityThresholds.fromScore(s.getScore()))
            .orElse(null);
        f.setPriority(priority);
        f.setSeverity(priority == null ? null : com.martecyber.ares.common.PriorityThresholds.severityForPriority(priority));
    }

    @Transactional
    public FindingDto addReference(Long findingId, Long projectId, Long referenceId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        ReferenceEntry ref = referenceEntryRepo.findById(referenceId)
            .orElseThrow(() -> NotFoundException.of("reference", referenceId));
        ref.getFindings().add(f);
        materializeIfCve(ref);
        referenceEntryRepo.save(ref);
        return buildFullDto(f);
    }

    @Transactional
    public FindingDto removeReference(Long findingId, Long projectId, Long referenceId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        referenceEntryRepo.findById(referenceId).ifPresent(ref -> {
            ref.getFindings().removeIf(fi -> fi.getId().equals(findingId));
            referenceEntryRepo.save(ref);
        });
        return buildFullDto(f);
    }

    @Transactional
    public FindingTemplateDto saveAsTemplate(Long findingId, Long projectId) {
        Finding f = repo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        requireOwnProject(f, projectId);
        OffsetDateTime now = OffsetDateTime.now();

        FindingTemplate tpl = new FindingTemplate();
        tpl.setSeverity(f.getSeverity());
        tpl.setTitle(f.getTitle());
        tpl.setCreatorId(currentUserId());
        tpl.setCreatedAt(now);
        tpl.setUpdatedAt(now);
        templateRepo.save(tpl);

        Map<String, FindingFieldType> typesByName = fieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .collect(Collectors.toMap(FindingFieldType::getName, t -> t));
        List<FindingTemplateField> tplFields = FindingFields.read(f).entrySet().stream()
            .filter(e -> typesByName.containsKey(e.getKey()))
            .map(e -> {
                FindingTemplateField tf = new FindingTemplateField();
                tf.setTemplateId(tpl.getId());
                tf.setTypeId(typesByName.get(e.getKey()).getId());
                tf.setFieldText(e.getValue());
                tf.setCreatedAt(now);
                tf.setUpdatedAt(now);
                return tf;
            }).toList();
        templateFieldRepo.saveAll(tplFields);

        List<FindingTemplateScore> tplScores = scoreRepo.findByFindingId(f.getId()).stream()
            .map(fs -> {
                FindingTemplateScore ts = new FindingTemplateScore();
                ts.setTemplateId(tpl.getId());
                ts.setTypeId(fs.getTypeId());
                ts.setScore(fs.getScore());
                ts.setMetadata(fs.getVector());
                ts.setSsvcLeafNodeId(fs.getSsvcLeafNodeId());
                ts.setDefault(fs.isDefault());
                ts.setCreatedAt(now);
                ts.setUpdatedAt(now);
                return ts;
            }).toList();
        templateScoreRepo.saveAll(tplScores);

        List<FindingTemplateDto.FieldDto> fieldDtos = tplFields.stream()
            .map(tf -> new FindingTemplateDto.FieldDto(tf.getId(), tf.getTypeId(), tf.getFieldText(), tf.getCreatedAt(), tf.getUpdatedAt()))
            .toList();
        List<FindingTemplateDto.ScoreDto> scoreDtos = tplScores.stream()
            .map(ts -> new FindingTemplateDto.ScoreDto(ts.getId(), ts.getTypeId(), ts.getScore(), ts.getMetadata(), ts.isDefault(), ts.getSsvcLeafNodeId(), ts.getCreatedAt(), ts.getUpdatedAt()))
            .toList();

        return new FindingTemplateDto(tpl.getId(), tpl.getSeverity(), tpl.getTitle(), tpl.getCreatorId(),
            fieldDtos, scoreDtos, java.util.List.of(), tpl.getCreatedAt(), tpl.getUpdatedAt(), java.util.List.of());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private FindingDto buildFullDto(Finding f) {
        Map<Long, String> statusNames = loadStatusNames();
        Map<Long, String> scoreTypeNames = scoreTypeRepo.findAll().stream()
            .collect(Collectors.toMap(FindingScoreType::getId, FindingScoreType::getTitle));
        Map<Long, String> catalogCodes = referenceCatalogRepo.findAll().stream()
            .collect(Collectors.toMap(c -> c.getId(), c -> c.getCode()));

        List<FindingDto.FieldDto> fields = fieldDtosFor(f);
        List<FindingDto.ScoreDto> scores = scoreRepo.findByFindingId(f.getId()).stream()
            .map(fs -> toScoreDto(fs, scoreTypeNames)).toList();
        List<FindingDto.StatusHistoryDto> history = historyRepo.findByIdFindingIdOrderByIdChangedAtAsc(f.getId()).stream()
            .map(h -> toHistoryDto(h, statusNames)).toList();

        // Affections with asset links (JOIN FETCH to avoid LazyInitializationException)
        List<Affection> affectionEntities = affectionRepo.findByFindingIdWithAssets(f.getId());

        // Load detections per affection (separate query to avoid MultipleBagFetchException)
        Map<Long, java.util.Set<com.martecyber.ares.detections.Detection>> detectionsByAffectionId =
            affectionRepo.findByFindingIdWithDetections(f.getId()).stream()
                .collect(Collectors.toMap(Affection::getId, Affection::getDetections));

        // Load per-detected_at affects links in bulk for all affections of this finding
        java.util.List<Long> affectionIds = affectionEntities.stream()
            .map(Affection::getId).toList();
        var allLinks = affectionIds.isEmpty()
            ? java.util.List.<com.martecyber.ares.affections.AffectionAffectsLink>of()
            : affectsLinkRepo.findByAffectionIdIn(affectionIds);
        // Pre-fetch asset details for the link targets we haven't already seen
        java.util.Set<Long> linkAssetIds = allLinks.stream()
            .map(com.martecyber.ares.affections.AffectionAffectsLink::getAffectsAssetId)
            .collect(java.util.stream.Collectors.toSet());
        var linkedAssetMap = linkAssetIds.isEmpty()
            ? java.util.Map.<Long, com.martecyber.ares.assets.Asset>of()
            : assetRepo.findAllById(linkAssetIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.martecyber.ares.assets.Asset::getId, a -> a));
        // Group by (affectionId, detectedAssetId) → list of AffectRef
        var linksByDetected = new java.util.HashMap<Long, java.util.Map<Long, java.util.List<FindingDto.AffectionDto.AffectRef>>>();
        for (var l : allLinks) {
            var asset = linkedAssetMap.get(l.getAffectsAssetId());
            if (asset == null) continue;
            // Affect status (open/closed) lives on the legacy affection_asset row.
            // Re-derive it from the entity to keep both views consistent.
            linksByDetected
                .computeIfAbsent(l.getAffectionId(), k -> new java.util.HashMap<>())
                .computeIfAbsent(l.getDetectedAssetId(), k -> new java.util.ArrayList<>())
                .add(new FindingDto.AffectionDto.AffectRef(
                    String.valueOf(l.getAffectsAssetId()), asset.getType(),
                    asset.getIdentifier(), null /* status filled below */));
        }

        List<FindingDto.AffectionDto> affections = affectionEntities.stream()
            .map(aff -> {
                java.util.Set<com.martecyber.ares.detections.Detection> affDetections =
                    detectionsByAffectionId.getOrDefault(aff.getId(), java.util.Set.of());
                // Build the global per-affection affects list with status from affection_asset
                java.util.Map<Long, String> affectStatusByAssetId = aff.getAssetLinks().stream()
                    .filter(l -> "affects".equals(l.getRole()))
                    .collect(java.util.stream.Collectors.toMap(
                        com.martecyber.ares.affections.AffectionAsset::getAssetId,
                        l -> l.getStatus() != null ? l.getStatus() : "open",
                        (a, b) -> a));
                List<FindingDto.AffectionDto.AffectRef> affects = aff.getAssetLinks().stream()
                    .filter(l -> "affects".equals(l.getRole()) && l.getAsset() != null)
                    .map(l -> new FindingDto.AffectionDto.AffectRef(
                        String.valueOf(l.getAssetId()), l.getAsset().getType(),
                        l.getAsset().getIdentifier(), l.getStatus()))
                    .toList();

                var detectedLinks = linksByDetected.getOrDefault(aff.getId(), java.util.Map.of());
                List<FindingDto.AffectionDto.DetectedAtRef> detectedAt = aff.getAssetLinks().stream()
                    .filter(l -> "detected_at".equals(l.getRole()) && l.getAsset() != null)
                    .map(l -> {
                        List<FindingDto.AffectionDto.DetectionRef> dets = affDetections.stream()
                            .filter(d -> l.getAssetId().equals(d.getAssetId()))
                            .map(d -> new FindingDto.AffectionDto.DetectionRef(d.getId(), d.getTitle(), d.getStatus(), d.getSeverity()))
                            .toList();
                        // Enrich each linked AffectRef with the (open/closed) status pulled
                        // from the affection_asset row for that affect asset.
                        List<FindingDto.AffectionDto.AffectRef> raw = detectedLinks.getOrDefault(
                            l.getAssetId(), java.util.List.of());
                        List<FindingDto.AffectionDto.AffectRef> withStatus = raw.stream()
                            .map(r -> new FindingDto.AffectionDto.AffectRef(
                                r.id(), r.type(), r.identifier(),
                                affectStatusByAssetId.getOrDefault(Long.valueOf(r.id()), "open")))
                            .toList();
                        return new FindingDto.AffectionDto.DetectedAtRef(
                            String.valueOf(l.getAssetId()), l.getAsset().getType(),
                            l.getAsset().getIdentifier(), l.getObservedAt(), dets, withStatus);
                    })
                    .toList();
                return new FindingDto.AffectionDto(aff.getId(), aff.getCode(), aff.getTitle(), aff.getDescription(),
                    detectedAt, affects, aff.getStatus(), aff.getCreatedAt(), aff.getUpdatedAt());
            }).toList();

        // Derived remediation status (open/closed): null for drafts
        String remediationStatus = null;
        if (!f.isDraft()) {
            boolean allClosed = !affections.isEmpty()
                && affections.stream().allMatch(a -> "closed".equals(a.status()));
            remediationStatus = allClosed ? "closed" : "open";
        }

        // References via the ReferenceEntry M:M
        List<FindingDto.ReferenceDto> references = referenceEntryRepo.findByFindingId(f.getId()).stream()
            .map(re -> new FindingDto.ReferenceDto(re.getId(), re.getCatalogId(),
                catalogCodes.getOrDefault(re.getCatalogId(), ""), re.getTitle(), re.getDescription(),
                re.getUrl(), re.getFaviconUrl()))
            .toList();

        List<com.martecyber.ares.tags.TagDto> tags = tagRepo.findTagsForFindingIds(List.of(f.getId())).stream()
            .map(row -> new com.martecyber.ares.tags.TagDto(row.getId(), null, row.getName(), row.getColor())).toList();
        return toDto(f, statusNames, fields, scores, affections, references, history, remediationStatus).withTags(tags);
    }

    private Map<Long, String> loadStatusNames() {
        return statusRepo.findAll().stream()
            .collect(Collectors.toMap(FindingStatus::getId, FindingStatus::getName));
    }

    @Transactional
    public FindingDto markReadyToReport(Long id, Long projectId, boolean ready) {
        Finding f = repo.findById(id).orElseThrow(() -> NotFoundException.of("finding", id));
        requireOwnProject(f, projectId);
        if (f.isDraft()) throw new IllegalStateException("Draft findings cannot be marked ready to report");
        f.setReadyToReport(ready);
        f.setUpdatedAt(OffsetDateTime.now());
        repo.save(f);
        return buildFullDto(f);
    }

    private static FindingDto toDtoShort(Finding f, Map<Long, String> statusNames, String remediationStatus) {
        return new FindingDto(f.getId(), f.getProjectId(), f.getCode(),
            f.getSeverity(), f.getTitle(),
            f.getStatusId(), statusNames.getOrDefault(f.getStatusId(), ""), f.getCreatorId(),
            f.isDraft(), f.isReadyToReport(), f.getReportedAt(), f.getResolvedAt(), f.getDueDate(), f.getIterationLabel(),
            List.of(), List.of(), List.of(), List.of(), List.of(), remediationStatus,
            f.getCreatedAt(), f.getUpdatedAt(), List.of());
    }

    private static FindingDto toDto(Finding f, Map<Long, String> statusNames,
                                     List<FindingDto.FieldDto> fields,
                                     List<FindingDto.ScoreDto> scores,
                                     List<FindingDto.AffectionDto> affections,
                                     List<FindingDto.ReferenceDto> references,
                                     List<FindingDto.StatusHistoryDto> history,
                                     String remediationStatus) {
        return new FindingDto(f.getId(), f.getProjectId(), f.getCode(),
            f.getSeverity(), f.getTitle(),
            f.getStatusId(), statusNames.getOrDefault(f.getStatusId(), ""), f.getCreatorId(),
            f.isDraft(), f.isReadyToReport(), f.getReportedAt(), f.getResolvedAt(), f.getDueDate(), f.getIterationLabel(),
            fields, scores, affections, references, history, remediationStatus,
            f.getCreatedAt(), f.getUpdatedAt(), List.of());
    }

    private static FindingDto.ScoreDto toScoreDto(FindingScore fs, Map<Long, String> typeNames) {
        return new FindingDto.ScoreDto(fs.getId(), fs.getTypeId(),
            typeNames.getOrDefault(fs.getTypeId(), ""), fs.getScore(),
            fs.getVector(), fs.isDefault(), fs.getComment(), fs.getMetadata(),
            fs.getSsvcLeafNodeId(), fs.getCreatedAt(), fs.getUpdatedAt());
    }

    private static FindingDto.StatusHistoryDto toHistoryDto(FindingStatusHistory h, Map<Long, String> statusNames) {
        return new FindingDto.StatusHistoryDto(
            h.getId().getFindingId(), h.getId().getFindingStatusId(),
            statusNames.getOrDefault(h.getId().getFindingStatusId(), ""),
            h.getId().getChangedAt());
    }

    private static FindingFieldTypeDto toFieldTypeDto(FindingFieldType t) {
        return new FindingFieldTypeDto(t.getId(), t.getName(), t.getTitle(), t.getDescription(),
            t.isSystem(), t.isRequired(), t.getSortOrder());
    }

    // ── Field type admin operations ───────────────────────────────────────────

    @Transactional
    public FindingFieldTypeDto createFieldType(CreateFindingFieldTypeRequest req) {
        if (fieldTypeRepo.existsByName(req.name())) {
            throw new IllegalArgumentException("A field type with name '" + req.name() + "' already exists");
        }
        FindingFieldType t = new FindingFieldType();
        t.setName(req.name());
        t.setTitle(req.title().trim());
        t.setDescription(req.description());
        t.setRequired(req.required());
        t.setSortOrder(req.sortOrder());
        t.setSystem(false);
        t.setCreatedAt(OffsetDateTime.now());
        return toFieldTypeDto(fieldTypeRepo.save(t));
    }

    @Transactional
    public FindingFieldTypeDto updateFieldType(Long id, UpdateFindingFieldTypeRequest req) {
        FindingFieldType t = fieldTypeRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("finding_field_type", id));
        if (!t.isSystem()) {
            if (req.title() != null && !req.title().isBlank()) t.setTitle(req.title().trim());
            if (req.description() != null) t.setDescription(req.description());
            if (req.sortOrder() != null) t.setSortOrder(req.sortOrder());
        }
        if (req.required() != null) {
            if (t.isSystem() && Boolean.FALSE.equals(req.required())) {
                throw new IllegalStateException("System fields cannot be made non-required");
            }
            t.setRequired(req.required());
        }
        return toFieldTypeDto(fieldTypeRepo.save(t));
    }

    @Transactional
    public void deleteFieldType(Long id) {
        FindingFieldType t = fieldTypeRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("finding_field_type", id));
        if (t.isSystem()) throw new IllegalStateException("System field types cannot be deleted");
        fieldTypeRepo.deleteById(id);
    }

    /** Generates the next sequential affection code: {findingCode}-{n} */
    public String generateAffectionCode(Long findingId, String findingCodePrefix) {
        long next = affectionRepo.countByFindingId(findingId) + 1;
        return findingCodePrefix + "-" + next;
    }
}
