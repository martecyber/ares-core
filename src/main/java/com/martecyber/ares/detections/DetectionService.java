package com.martecyber.ares.detections;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.dto.CreateDetectionRequest;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.detections.dto.EscalateBatchRequest;
import com.martecyber.ares.detections.dto.EscalateDetectionRequest;
import com.martecyber.ares.detections.dto.EscalationResultDto;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingScoreTypeRepository;
import com.martecyber.ares.findings.FindingStatus;
import com.martecyber.ares.findings.FindingStatusHistory;
import com.martecyber.ares.findings.FindingStatusHistoryRepository;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.references.ReferenceService;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.tags.TagDto;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DetectionService {

    private final DetectionRepository repo;
    private final DetectionAffectedAssetRepository affectedRepo;
    private final AffectionRepository affectionRepo;
    private final FindingRepository findingRepo;
    private final FindingScoreTypeRepository scoreTypeRepo;
    private final FindingStatusRepository findingStatusRepo;
    private final FindingStatusHistoryRepository findingStatusHistoryRepo;
    private final AssetRepository assetRepo;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final ReferenceCatalogRepository referenceCatalogRepo;
    private final ReferenceService referenceService;
    private final CveRepository cveRepo;
    private final DetectionStatusHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final DetectionTagRepository tagRepo;
    private final com.martecyber.ares.tags.TagRepository tagCatalogRepo;
    private final ProjectRepository projectRepo;
    private final DetectionIterationStatsService iterationStatsService;
    private final OrgScopeService orgScope;
    private final DetectionAqlRegistry aqlRegistry;
    private final com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    private final DetectionStatusRepository statusRepo;
    private final DetectionStatusTransitionRepository statusTransitionRepo;

    public DetectionService(DetectionRepository repo,
                            DetectionAffectedAssetRepository affectedRepo,
                            AffectionRepository affectionRepo,
                            FindingRepository findingRepo,
                            FindingScoreTypeRepository scoreTypeRepo,
                            FindingStatusRepository findingStatusRepo,
                            FindingStatusHistoryRepository findingStatusHistoryRepo,
                            AssetRepository assetRepo,
                            ReferenceCatalogRepository referenceCatalogRepo,
                            ReferenceService referenceService,
                            CveRepository cveRepo,
                            DetectionStatusHistoryRepository historyRepo,
                            UserRepository userRepo,
                            DetectionTagRepository tagRepo,
                            com.martecyber.ares.tags.TagRepository tagCatalogRepo,
                            ProjectRepository projectRepo,
                            DetectionIterationStatsService iterationStatsService,
                            OrgScopeService orgScope,
                            DetectionAqlRegistry aqlRegistry,
                            DetectionStatusRepository statusRepo,
                            DetectionStatusTransitionRepository statusTransitionRepo,
                            WorkflowEventDispatcher workflowEventDispatcher,
                            com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander) {
        this.aqlVariableExpander = aqlVariableExpander;
        this.workflowEventDispatcher = workflowEventDispatcher;
        this.repo = repo;
        this.aqlRegistry = aqlRegistry;
        this.statusRepo = statusRepo;
        this.statusTransitionRepo = statusTransitionRepo;
        this.tagRepo = tagRepo;
        this.tagCatalogRepo = tagCatalogRepo;
        this.projectRepo = projectRepo;
        this.iterationStatsService = iterationStatsService;
        this.orgScope = orgScope;
        this.affectedRepo = affectedRepo;
        this.affectionRepo = affectionRepo;
        this.findingRepo = findingRepo;
        this.scoreTypeRepo = scoreTypeRepo;
        this.findingStatusRepo = findingStatusRepo;
        this.findingStatusHistoryRepo = findingStatusHistoryRepo;
        this.assetRepo = assetRepo;
        this.referenceCatalogRepo = referenceCatalogRepo;
        this.referenceService = referenceService;
        this.cveRepo = cveRepo;
        this.historyRepo = historyRepo;
        this.userRepo = userRepo;
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** True for both client tiers (CLIENT_USER and CLIENT_ADMIN). */
    private static boolean isClientUser(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_CLIENT_USER".equals(a.getAuthority()) || "ROLE_CLIENT_ADMIN".equals(a.getAuthority()));
    }

    /** Detections are only visible to clients when the project has explicitly opted in
     *  (Project.clientsCanViewDetections, set from the project's edit dialog by MSSP staff). */
    private void assertClientDetectionAccess(Authentication auth, Long projectId) {
        if (!isClientUser(auth)) return;
        Project p = projectRepo.findById(projectId).orElseThrow(() -> NotFoundException.of("project", projectId));
        if (!p.isClientsCanViewDetections()) {
            throw new AccessDeniedException("Detections are not visible to clients for this project");
        }
    }

    private java.util.Map<Long, String> loadCatalogCodes() {
        return referenceCatalogRepo.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                com.martecyber.ares.references.ReferenceCatalog::getId,
                com.martecyber.ares.references.ReferenceCatalog::getCode));
    }

    /** Batched KEV/PoC lookup for every CVE reference across a set of detections — one query
     *  regardless of how many detections/CVEs are involved, so list-view rendering doesn't pay
     *  an N+1 cost. CveEntry already denormalizes anyKevListed/exploitCount (see CveEntry), so
     *  this needs no join into the KEV/exploit tables directly. */
    private java.util.Map<String, DetectionDto.CvePriority> loadCvePriorities(
            java.util.Collection<Detection> detections, java.util.Map<Long, String> catalogCodes) {
        var cveIds = detections.stream()
            .flatMap(d -> d.getReferences().stream())
            .filter(r -> "CVE".equals(catalogCodes.get(r.getCatalogId())))
            .map(com.martecyber.ares.references.ReferenceEntry::getTitle)
            .collect(java.util.stream.Collectors.toSet());
        if (cveIds.isEmpty()) return java.util.Map.of();
        return cveRepo.findAllByCveIdIn(cveIds).stream()
            .collect(java.util.stream.Collectors.toMap(CveEntry::getCveId,
                e -> new DetectionDto.CvePriority(e.isAnyKevListed(), e.getExploitCount())));
    }

    @Transactional
    public Page<DetectionDto> list(Long projectId, java.util.List<Long> assetIds,
                                   java.util.List<String> severities, java.util.List<String> statuses,
                                   java.util.List<String> sources,
                                   String q, String sortBy, String sortDir,
                                   int page, int size) {
        org.springframework.data.domain.Sort sort = buildSort(sortBy, sortDir);
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), sort);
        String cleanQ = blank(q);
        String qLike = cleanQ != null ? "%" + cleanQ.toLowerCase() + "%" : null;

        boolean noAssetFilter = assetIds == null || assetIds.isEmpty();
        boolean noSeverityFilter = severities == null || severities.isEmpty();
        boolean noStatusFilter = statuses == null || statuses.isEmpty();
        boolean noSourceFilter = sources == null || sources.isEmpty();

        java.util.Collection<Long> orgIds = resolveDetectionOrgScope(projectId, null);
        if (orgIds != null && orgIds.isEmpty()) return new PageImpl<>(java.util.List.of(), p, 0);

        var detPage = repo.filter(projectId, orgIds,
            noAssetFilter, noAssetFilter ? java.util.List.of(-1L) : assetIds,
            noSeverityFilter, noSeverityFilter ? java.util.List.of("__none__") : severities,
            noStatusFilter, noStatusFilter ? java.util.List.of("__none__") : statuses,
            noSourceFilter, noSourceFilter ? java.util.List.of("__none__") : sources,
            cleanQ, qLike, p);

        return mapToDto(detPage);
    }

    /**
     * AQL-driven listing (AQL implementation plan, Phase 1) — coexists with {@link #list} rather
     * than replacing it; when a caller supplies {@code aql} the API layer ignores the discrete
     * filter params entirely rather than merging the two filtering modes.
     */
    @Transactional
    public Page<DetectionDto> listByAql(Long projectId, String aql, String sortBy, String sortDir, int page, int size) {
        return listByAql(projectId, null, aql, sortBy, sortDir, page, size);
    }

    /** Organization-wide variant (Workflow ASSIGN_VARIABLE nodes need this — Asset/Finding's
     *  listByAql already support an org-wide scope, this one didn't) — mirrors Finding's
     *  resolveFindingOrgScope(projectId, orgId) shape exactly. */
    @Transactional
    public Page<DetectionDto> listByAql(Long projectId, Long organizationId, String aql, String sortBy, String sortDir, int page, int size) {
        org.springframework.data.domain.Sort sort = buildSort(sortBy, sortDir);
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), sort);

        java.util.Collection<Long> orgIds = resolveDetectionOrgScope(projectId, organizationId);
        if (orgIds != null && orgIds.isEmpty()) return new PageImpl<>(java.util.List.of(), p, 0);

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Detection> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgIds));

        return mapToDto(repo.findAll(spec, p));
    }

    /** Count-only variant of {@link #listByAql} — same scope resolution and AQL compilation, no
     *  paging/sort/DTO-mapping, for Workflow CONDITION nodes comparing result counts instead of
     *  fetching rows. */
    @Transactional
    public long countByAql(Long projectId, Long organizationId, String aql) {
        java.util.Collection<Long> orgIds = resolveDetectionOrgScope(projectId, organizationId);
        if (orgIds != null && orgIds.isEmpty()) return 0;

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Detection> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgIds));
        return repo.count(spec);
    }

    /** Grouped-count variant of {@link #countByAql} for the dashboard AQL_CHART widget — same
     *  scope resolution and AQL compilation, grouped by {@code groupByField} instead of a single
     *  total. See {@link com.martecyber.ares.aql.compile.AqlGroupCountSupport}.
     *
     *  <p>{@code dateBucket == "iteration"} is handled entirely here instead: Detection has no
     *  iteration column of its own (unlike {@code Finding.iterationLabel}) — iteration membership
     *  lives in the separate {@link DetectionIterationStat} table, one row per (project,
     *  detection, iteration, area). Only meaningful at PROJECT scope. */
    @Transactional
    public List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> countGroupedByAql(
            Long projectId, Long organizationId, String aql, String groupByField, String dateBucket,
            String seriesField, Integer topN, String sortMode) {
        java.util.Collection<Long> orgIds = resolveDetectionOrgScope(projectId, organizationId);
        if (orgIds != null && orgIds.isEmpty()) return List.of();

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Detection> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(scopeSpecification(projectId, orgIds));
        if ("iteration".equals(dateBucket)) {
            if (projectId == null) {
                throw new com.martecyber.ares.aql.compile.AqlCompileException(
                    "Bucketing detections by iteration is only available on a project dashboard");
            }
            return countGroupedByIteration(projectId, spec, seriesField, topN, sortMode);
        }
        return com.martecyber.ares.aql.compile.AqlGroupCountSupport.execute(
            em, spec, Detection.class, aqlRegistry, groupByField, dateBucket, seriesField, topN, sortMode);
    }

    /** Joins the AQL-filtered {@code Detection} set to {@link DetectionIterationStat} on
     *  {@code detectionId} (+ matching project), grouping by the stat's {@code iterationLabel}
     *  instead of any column on {@code Detection} itself — a genuine theta-join between two
     *  otherwise-unrelated roots, which is why this can't go through the single-root
     *  {@link com.martecyber.ares.aql.compile.AqlGroupCountSupport}. {@code seriesField} (if any)
     *  is still resolved against the {@code Detection} root. topN/sort reuses the same
     *  Java-side post-processing {@code AqlGroupCountSupport} uses, over the raw label/series/
     *  count rows this produces, so "(Other)" folding and sort ordering behave identically. */
    private List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> countGroupedByIteration(
            Long projectId, org.springframework.data.jpa.domain.Specification<Detection> spec,
            String seriesField, Integer topN, String sortMode) {
        jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        jakarta.persistence.criteria.Root<Detection> detRoot = cq.from(Detection.class);
        jakarta.persistence.criteria.Root<DetectionIterationStat> statRoot = cq.from(DetectionIterationStat.class);

        jakarta.persistence.criteria.Predicate joinPredicate = cb.and(
            cb.equal(statRoot.get("detectionId"), detRoot.get("id")),
            cb.equal(statRoot.get("projectId"), projectId));
        jakarta.persistence.criteria.Predicate aqlPredicate = spec.toPredicate(detRoot, cq, cb);

        jakarta.persistence.criteria.Expression<?> seriesExpr = null;
        if (seriesField != null && !seriesField.isBlank()) {
            var field = aqlRegistry.field(seriesField)
                .orElseThrow(() -> new com.martecyber.ares.aql.compile.AqlCompileException(
                    "Unknown field '" + seriesField + "' for " + aqlRegistry.entityName()));
            if (!(field instanceof com.martecyber.ares.aql.registry.PostgresColumnField<Detection> columnField)) {
                throw new com.martecyber.ares.aql.compile.AqlCompileException(
                    "'" + seriesField + "' can't be used as a series field — pick a plain field, not a relation");
            }
            seriesExpr = columnField.resolvePath(detRoot);
        }

        if (seriesExpr != null) {
            cq.multiselect(statRoot.get("iterationLabel"), seriesExpr, cb.countDistinct(detRoot.get("id")));
            cq.where(cb.and(joinPredicate, aqlPredicate));
            cq.groupBy(statRoot.get("iterationLabel"), seriesExpr);
        } else {
            cq.multiselect(statRoot.get("iterationLabel"), cb.countDistinct(detRoot.get("id")));
            cq.where(cb.and(joinPredicate, aqlPredicate));
            cq.groupBy(statRoot.get("iterationLabel"));
        }
        cq.orderBy(cb.asc(statRoot.get("iterationLabel")));

        List<jakarta.persistence.Tuple> rows = em.createQuery(cq).getResultList();
        List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> raw = new java.util.ArrayList<>();
        for (jakarta.persistence.Tuple t : rows) {
            String label = String.valueOf(t.get(0));
            String series = seriesExpr == null ? null : (t.get(1) == null ? "(none)" : String.valueOf(t.get(1)));
            long count = t.get(seriesExpr == null ? 1 : 2, Long.class);
            raw.add(new com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount(label, series, count));
        }
        return com.martecyber.ares.aql.compile.AqlGroupCountSupport.applyTopNAndSort(raw, topN, sortMode, true);
    }

    /**
     * Mirrors {@link #list}'s access checks: a specific project requires explicit access (plus
     * the client-visibility gate); a client with no project is always rejected (no org-wide
     * Detections view exists for clients); otherwise non-admins are restricted to their
     * accessible orgs. Returns null when the caller isn't limited to a specific org set —
     * callers must still separately check an empty (non-null) result for a short-circuit.
     */
    private java.util.Collection<Long> resolveDetectionOrgScope(Long projectId, Long organizationId) {
        var auth = currentAuth();
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
            assertClientDetectionAccess(auth, projectId);
            return null;
        }
        if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            return java.util.List.of(organizationId);
        }
        if (isClientUser(auth)) {
            // Clients only ever reach this endpoint scoped to a single project (the
            // project-level Detections tab) — there is no org-wide detections view for them.
            throw new AccessDeniedException("Detections must be requested for a specific project");
        }
        if (!orgScope.isPlatformAdmin(auth)) {
            return orgScope.accessibleOrgIds(auth);
        }
        return null;
    }

    /** Theta-join scope predicate mirroring DetectionRepository.filter's JOIN Project p ON p.id = d.projectId — Detection has no mapped association to Project, only a bare projectId column. */
    private org.springframework.data.jpa.domain.Specification<Detection> scopeSpecification(
            Long projectId, java.util.Collection<Long> orgIds) {
        return (root, query, cb) -> {
            if (projectId != null) {
                return cb.equal(root.get("projectId"), projectId);
            }
            if (orgIds != null) {
                var projectRoot = query.from(Project.class);
                return cb.and(
                    cb.equal(projectRoot.get("id"), root.get("projectId")),
                    projectRoot.get("organizationId").in(orgIds));
            }
            return cb.conjunction();
        };
    }

    private Page<DetectionDto> mapToDto(Page<Detection> detPage) {
        var detectionIds = detPage.getContent().stream().map(Detection::getId).toList();
        var affectedRows = detectionIds.isEmpty()
            ? java.util.List.<DetectionAffectedAsset>of()
            : affectedRepo.findByDetectionIdIn(detectionIds);
        var allAssetIds = new java.util.HashSet<Long>();
        for (Detection d : detPage.getContent()) {
            if (d.getAssetId() != null) allAssetIds.add(d.getAssetId());
        }
        for (DetectionAffectedAsset r : affectedRows) allAssetIds.add(r.getAssetId());
        var assetMap = assetRepo.findAllById(allAssetIds).stream()
            .collect(java.util.stream.Collectors.toMap(Asset::getId, a -> a));
        java.util.Map<Long, java.util.List<DetectionDto.AffectedAssetRef>> affectedByDet = affectedRows.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                DetectionAffectedAsset::getDetectionId,
                java.util.stream.Collectors.mapping(r -> toAffectedRef(r.getAssetId(), assetMap),
                    java.util.stream.Collectors.toList())));
        java.util.Map<Long, String> catalogCodes = loadCatalogCodes();
        java.util.Map<String, DetectionDto.CvePriority> cvePriorities = loadCvePriorities(detPage.getContent(), catalogCodes);
        java.util.Map<Long, java.util.List<TagDto>> tagsByDet = loadTagsByDetectionIds(detectionIds);
        return detPage.map(d -> DetectionDto.from(d, java.util.Map.of(), assetMap,
                affectedByDet.getOrDefault(d.getId(), java.util.List.of()), catalogCodes, cvePriorities)
            .withTags(tagsByDet.getOrDefault(d.getId(), java.util.List.of())));
    }

    private java.util.Map<Long, java.util.List<TagDto>> loadTagsByDetectionIds(java.util.List<Long> detectionIds) {
        if (detectionIds.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, java.util.List<TagDto>> byDet = new java.util.HashMap<>();
        for (DetectionTagRepository.DetectionTagRow row : tagRepo.findTagsForDetectionIds(detectionIds)) {
            byDet.computeIfAbsent(row.getDetectionId(), k -> new java.util.ArrayList<>())
                .add(new TagDto(row.getId(), null, row.getName(), row.getColor()));
        }
        return byDet;
    }

    private static DetectionDto.AffectedAssetRef toAffectedRef(Long assetId,
                                                                java.util.Map<Long, Asset> assetMap) {
        Asset a = assetMap.get(assetId);
        if (a == null) return new DetectionDto.AffectedAssetRef(assetId, null, null, null);
        return new DetectionDto.AffectedAssetRef(a.getId(), a.getCode(), a.getIdentifier(), a.getType());
    }

    @Transactional
    public java.util.List<String> listSources(Long projectId) {
        orgScope.assertProjectAccess(currentAuth(), projectId);
        assertClientDetectionAccess(currentAuth(), projectId);
        return repo.findDistinctSources(projectId);
    }

    public record AssetRef(Long id, String code, String identifier) {}

    @Transactional
    public java.util.List<AssetRef> listDetectionAssets(Long projectId) {
        orgScope.assertProjectAccess(currentAuth(), projectId);
        assertClientDetectionAccess(currentAuth(), projectId);
        var ids = repo.findDistinctAssetIds(projectId);
        if (ids.isEmpty()) return java.util.List.of();
        return assetRepo.findAllById(ids).stream()
            .map(a -> new AssetRef(a.getId(), a.getCode(), a.getIdentifier()))
            .sorted(java.util.Comparator.comparing(r -> r.identifier() != null ? r.identifier() : ""))
            .toList();
    }

    private org.springframework.data.domain.Sort buildSort(String sortBy, String sortDir) {
        org.springframework.data.domain.Sort.Direction dir =
            "asc".equalsIgnoreCase(sortDir)
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;
        String col = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            // "severity" (the sortBy key the UI already sends for this column) now sorts by the
            // real canonical priority column directly — the old severityWeight @Formula bridge
            // (5=critical..1=info, the inverse of priority's 0=critical..4=info) was retired
            // because nothing kept it in sync with priority becoming the source of truth, and
            // ascending-by-severityWeight silently sorted least-urgent-first instead of the
            // most-urgent-first an ascending sort on a "0=most urgent" column should give.
            case "severity"  -> "priority";
            case "title"     -> "title";
            case "lastseen"  -> "lastSeen";
            case "status"    -> "status";
            case "source"    -> "sourceType";
            default          -> "createdAt";
        };
        return org.springframework.data.domain.Sort.by(dir, col);
    }

    @Transactional
    public DetectionDto get(Long id) {
        Detection d = repo.findByIdWithReferences(id)
            .orElseThrow(() -> NotFoundException.of("detection", id));
        orgScope.assertProjectAccess(currentAuth(), d.getProjectId());
        assertClientDetectionAccess(currentAuth(), d.getProjectId());
        // trigger lazy load of scores within the transaction
        d.getScores().size();
        java.util.Map<Long, String> scoreTypeNames = scoreTypeRepo.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                com.martecyber.ares.findings.FindingScoreType::getId,
                com.martecyber.ares.findings.FindingScoreType::getTitle));
        var affectedRows = affectedRepo.findByDetectionId(id);
        var assetIds = new java.util.HashSet<Long>();
        if (d.getAssetId() != null) assetIds.add(d.getAssetId());
        for (DetectionAffectedAsset r : affectedRows) assetIds.add(r.getAssetId());
        var assetMap = assetRepo.findAllById(assetIds).stream()
            .collect(java.util.stream.Collectors.toMap(Asset::getId, a -> a));
        var affectedRefs = affectedRows.stream()
            .map(r -> toAffectedRef(r.getAssetId(), assetMap)).toList();
        var tags = tagRepo.findTagsForDetectionIds(java.util.List.of(id)).stream()
            .map(row -> new TagDto(row.getId(), null, row.getName(), row.getColor())).toList();
        var catalogCodes = loadCatalogCodes();
        var cvePriorities = loadCvePriorities(java.util.List.of(d), catalogCodes);
        return DetectionDto.from(d, scoreTypeNames, assetMap, affectedRefs, catalogCodes, cvePriorities).withTags(tags);
    }

    /** Assigns an existing tag to a detection — a platform tag (no organization of its own) or a
     *  tag belonging to the detection's own organization. */
    @Transactional
    public DetectionDto assignTag(Long detectionId, Long tagId) {
        Detection d = repo.findById(detectionId).orElseThrow(() -> NotFoundException.of("detection", detectionId));
        Long orgId = resolveOrgId(d);
        var tag = tagCatalogRepo.findById(tagId).orElseThrow(() -> NotFoundException.of("tag", tagId));
        if (tag.getOrganizationId() != null && !tag.getOrganizationId().equals(orgId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Tag belongs to a different organization");
        }
        tagRepo.assign(detectionId, tagId);
        return get(detectionId);
    }

    @Transactional
    public DetectionDto unassignTag(Long detectionId, Long tagId) {
        tagRepo.unassign(detectionId, tagId);
        return get(detectionId);
    }

    private Long resolveOrgId(Detection d) {
        Project project = projectRepo.findById(d.getProjectId())
            .orElseThrow(() -> NotFoundException.of("project", d.getProjectId()));
        return project.getOrganizationId();
    }

    @Transactional
    public DetectionDto create(CreateDetectionRequest req) {
        orgScope.assertProjectAccess(currentAuth(), req.projectId());
        Detection d = new Detection();
        d.setProjectId(req.projectId());
        d.setAssetId(req.assetId());
        d.setSeverity(req.severity());
        d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(req.severity()));
        DetectionStatus initialStatus = resolveStatus(req.status() != null ? req.status() : "new");
        d.setStatus(initialStatus.getName());
        d.setStatusId(initialStatus.getId());
        d.setTitle(req.title());
        d.setDescription(req.description());
        d.setRawData(req.rawData());
        OffsetDateTime now = OffsetDateTime.now();
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.setLastSeen(now);
        Detection saved = repo.save(d);
        // Default: detected_at asset is also the first "affected" asset. The
        // operator can widen the list later via PUT /affected-assets.
        if (saved.getAssetId() != null) {
            affectedRepo.save(new DetectionAffectedAsset(saved.getId(), saved.getAssetId()));
        }
        recordHistory(saved.getId(), "created", null, saved.getStatus(),
            currentUserId(), currentUserName(), null, now);
        workflowEventDispatcher.onDetectionCreated(saved);
        return DetectionDto.from(saved, java.util.Map.of());
    }

    /** Outcome of an external-source ingest: the detection plus whether it was newly created. */
    public record IngestResult(Detection detection, boolean created) {}

    /**
     * Idempotently ingests a detection from an external source (e.g. a Caido finding).
     * Dedup key is {@code (projectId, sourceType, sourceTemplateId)}. On a repeat the existing
     * detection's occurrence count and {@code lastSeen} are bumped; otherwise a new detection is
     * created with full provenance and the normal create side-effects (affected asset + messaging).
     */
    @Transactional
    public IngestResult ingestExternal(Long projectId, String sourceType, String externalId,
                                       Long assetId, String severity, String title,
                                       String description, String rawData) {
        OffsetDateTime now = OffsetDateTime.now();
        var existing = repo.findFirstByProjectIdAndSourceTypeAndSourceTemplateId(projectId, sourceType, externalId);
        if (existing.isPresent()) {
            Detection d = existing.get();
            d.setOccurrenceCount(d.getOccurrenceCount() + 1);
            d.setLastSeen(now);
            d.setUpdatedAt(now);
            Detection saved = repo.save(d);
            recordHistory(saved.getId(), "reseen", null, null, null, null, null, now);
            return new IngestResult(saved, false);
        }
        Detection d = new Detection();
        d.setProjectId(projectId);
        d.setAssetId(assetId);
        String initialSeverity = severity != null ? severity : "info";
        d.setSeverity(initialSeverity);
        d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(initialSeverity));
        DetectionStatus newStatus = resolveStatus("new");
        d.setStatus(newStatus.getName());
        d.setStatusId(newStatus.getId());
        d.setTitle(title);
        d.setDescription(description);
        d.setRawData(rawData);
        d.setSourceType(sourceType);
        d.setSourceTemplateId(externalId);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.setLastSeen(now);
        Detection saved = repo.save(d);
        if (saved.getAssetId() != null) {
            affectedRepo.save(new DetectionAffectedAsset(saved.getId(), saved.getAssetId()));
        }
        recordHistory(saved.getId(), "created", null, saved.getStatus(), null, null, null, now);
        workflowEventDispatcher.onDetectionCreated(saved);
        return new IngestResult(saved, true);
    }

    /**
     * Replaces the entire set of affected assets for a detection. Pass an empty
     * list to clear it. Caller is expected to pass assets that belong to the
     * detection's organisation; the FK on the bridge table rejects invalid IDs.
     */
    @Transactional
    public DetectionDto replaceAffectedAssets(Long detectionId, java.util.List<Long> assetIds) {
        Detection d = repo.findById(detectionId)
            .orElseThrow(() -> NotFoundException.of("detection", detectionId));
        java.util.List<Long> distinctIds = assetIds == null ? java.util.List.of()
            : assetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!distinctIds.isEmpty()) {
            var found = assetRepo.findAllById(distinctIds);
            if (found.size() != distinctIds.size()) {
                throw new IllegalArgumentException("Some affected assets do not exist");
            }
        }
        affectedRepo.deleteByDetectionId(detectionId);
        affectedRepo.flush();
        for (Long aid : distinctIds) {
            affectedRepo.save(new DetectionAffectedAsset(detectionId, aid));
        }
        d.setUpdatedAt(OffsetDateTime.now());
        repo.save(d);
        return get(detectionId);
    }

    @Transactional
    public DetectionDto updateStatus(Long id, String newStatus, String note) {
        Detection d = repo.findById(id).orElseThrow(() -> NotFoundException.of("detection", id));
        DetectionStatus resolvedNewStatus = validateTransition(d.getStatus(), newStatus);
        String fromStatus = d.getStatus();
        OffsetDateTime now = OffsetDateTime.now();
        d.setStatus(resolvedNewStatus.getName());
        d.setStatusId(resolvedNewStatus.getId());
        d.setUpdatedAt(now);
        Detection saved = repo.save(d);
        recordHistory(saved.getId(), "status_changed", fromStatus, newStatus,
            currentUserId(), currentUserName(),
            (note != null && !note.isBlank()) ? note.trim() : null, now);
        workflowEventDispatcher.onDetectionUpdated(saved);
        return get(id);
    }

    @Transactional
    public DetectionDto escalate(Long detectionId, EscalateDetectionRequest req) {
        Detection detection = repo.findById(detectionId)
            .orElseThrow(() -> NotFoundException.of("detection", detectionId));

        if (!resolveStatus(detection.getStatus()).isEscalatable()) {
            throw new IllegalStateException(
                "Detection status '" + detection.getStatus() + "' cannot be escalated. Must be 'new' or 'reopened'.");
        }

        Finding finding;
        if ("new_finding".equals(req.mode())) {
            if (req.projectId() == null) throw new IllegalArgumentException("projectId required for new_finding mode");
            finding = createFinding(req, detection);
        } else if ("existing_finding".equals(req.mode())) {
            if (req.findingId() == null) throw new IllegalArgumentException("findingId required for existing_finding mode");
            finding = findingRepo.findById(req.findingId())
                .orElseThrow(() -> NotFoundException.of("finding", req.findingId()));
        } else {
            throw new IllegalArgumentException("mode must be 'new_finding' or 'existing_finding'");
        }
        Long findingId = finding.getId();

        // Carry over every KB reference (CVE, CWE, CAPEC, ATT&CK, OWASP, URL, …) linked to the
        // detection onto the finding — previously escalation dropped them, leaving the finding
        // with none of the external-database context the analyst had already attached upstream.
        for (var ref : detection.getReferences()) {
            referenceService.addFinding(ref.getId(), findingId);
        }

        // Create affection: link the detection + set detected_at from the detection's asset.
        // code follows the same {findingCode}-{n} convention as FindingService.generateAffectionCode
        // (a different class, so not reused directly) — this was previously never set at all here,
        // which made every escalate() call with mode=new_finding fail on affection's NOT NULL
        // constraint; found and fixed while verifying V143 exercised this path live.
        OffsetDateTime now = OffsetDateTime.now();
        long affSeq = affectionRepo.countByFindingId(findingId) + 1;
        Affection aff = new Affection();
        aff.setFindingId(findingId);
        aff.setCode(finding.getCode() + "-" + affSeq);
        aff.setCreatedAt(now);
        aff.setUpdatedAt(now);
        aff.getDetections().add(detection);
        affectionRepo.save(aff); // flush to get ID before adding asset links

        // The detection's scanned asset goes to detected_at; inherit detection's first-seen timestamp
        if (detection.getAssetId() != null) {
            java.time.OffsetDateTime when = detection.getCreatedAt() != null ? detection.getCreatedAt() : now;
            aff.addAssetLink(detection.getAssetId(), "detected_at", when);
        }
        // An explicitly provided asset goes to affects — any asset type is allowed
        if (req.assetId() != null) {
            assetRepo.findById(req.assetId())
                .orElseThrow(() -> NotFoundException.of("asset", req.assetId()));
            aff.addAssetLink(req.assetId(), "affects", null);
        }
        affectionRepo.save(aff);

        String fromStatus = detection.getStatus();
        DetectionStatus affectedStatus = resolveStatus("affected");
        detection.setStatus(affectedStatus.getName());
        detection.setStatusId(affectedStatus.getId());
        detection.setUpdatedAt(now);
        Detection saved = repo.save(detection);
        recordHistory(saved.getId(), "status_changed", fromStatus, "affected",
            currentUserId(), currentUserName(), null, now);
        return DetectionDto.from(saved, java.util.Map.of());
    }

    /**
     * The real, atomic multi-detection escalation action — see {@link EscalateBatchRequest}'s
     * own javadoc for why this exists alongside the older single-detection {@link #escalate}.
     * Called directly by the escalation wizard's final step (for both direct-detection and
     * Research Board escalation) once the target finding+affection are resolved — see
     * {@code ResearchBoardService.archiveAsAffected} for the board-specific bookkeeping that
     * follows this call.
     *
     * Eligibility is checked via the transition table ({@code current -> "affected"} must be an
     * allowed row) rather than {@code DetectionStatus.escalatable} — that flag only ever covered
     * new/reopened; the transition table already also allows under_investigation -> affected
     * (added alongside Research Boards), so checking it directly covers both paths through one
     * mechanism instead of special-casing research-originated detections.
     */
    @Transactional
    public EscalationResultDto escalateBatch(EscalateBatchRequest req) {
        if (req.detectionIds() == null || req.detectionIds().isEmpty()) {
            throw new IllegalArgumentException("detectionIds must not be empty");
        }
        List<Detection> detections = req.detectionIds().stream()
            .map(id -> repo.findById(id).orElseThrow(() -> NotFoundException.of("detection", id)))
            .toList();
        for (Detection d : detections) {
            validateTransition(d.getStatus(), "affected");
        }

        Finding finding;
        if ("new_finding".equals(req.findingMode())) {
            if (req.projectId() == null) throw new IllegalArgumentException("projectId required for new_finding mode");
            Detection representative = detections.get(0);
            finding = createFinding(req.projectId(), req.findingTitle(), req.findingSeverity(),
                representative.getTitle(), representative.getSeverity());
        } else if ("existing_finding".equals(req.findingMode())) {
            if (req.findingId() == null) throw new IllegalArgumentException("findingId required for existing_finding mode");
            finding = findingRepo.findById(req.findingId())
                .orElseThrow(() -> NotFoundException.of("finding", req.findingId()));
        } else {
            throw new IllegalArgumentException("findingMode must be 'new_finding' or 'existing_finding'");
        }
        Long findingId = finding.getId();

        OffsetDateTime now = OffsetDateTime.now();
        Affection aff;
        if ("existing_affection".equals(req.affectionMode())) {
            if (req.affectionId() == null) throw new IllegalArgumentException("affectionId required for existing_affection mode");
            aff = affectionRepo.findById(req.affectionId())
                .orElseThrow(() -> NotFoundException.of("affection", req.affectionId()));
            if (!aff.getFindingId().equals(findingId)) {
                throw new IllegalArgumentException("Affection " + req.affectionId() + " does not belong to finding " + findingId);
            }
        } else if ("new_affection".equals(req.affectionMode())) {
            long affSeq = affectionRepo.countByFindingId(findingId) + 1;
            aff = new Affection();
            aff.setFindingId(findingId);
            aff.setCode(finding.getCode() + "-" + affSeq);
            aff.setTitle(req.affectionTitle());
            aff.setDescription(req.affectionDescription());
            aff.setCreatedAt(now);
            aff.setUpdatedAt(now);
            affectionRepo.save(aff);
        } else {
            throw new IllegalArgumentException("affectionMode must be 'new_affection' or 'existing_affection'");
        }
        Long affectionId = aff.getId();

        // Union every detection's KB references (CVE/CWE/CAPEC/ATT&CK/OWASP/URL) onto the
        // finding — addFinding is Set-backed on the finding side, so duplicates across detections
        // collapse naturally.
        for (Detection d : detections) {
            for (var ref : d.getReferences()) {
                referenceService.addFinding(ref.getId(), findingId);
            }
        }

        for (Detection d : detections) {
            aff.getDetections().add(d);
            if (d.getAssetId() != null) {
                OffsetDateTime when = d.getCreatedAt() != null ? d.getCreatedAt() : now;
                aff.addAssetLink(d.getAssetId(), "detected_at", when);
            }
        }
        affectionRepo.save(aff);

        DetectionStatus affectedStatus = resolveStatus("affected");
        for (Detection d : detections) {
            String fromStatus = d.getStatus();
            d.setStatus(affectedStatus.getName());
            d.setStatusId(affectedStatus.getId());
            d.setUpdatedAt(now);
            Detection saved = repo.save(d);
            recordHistory(saved.getId(), "status_changed", fromStatus, "affected",
                currentUserId(), currentUserName(), null, now);
        }

        return new EscalationResultDto(findingId, affectionId, detections.size());
    }

    @Transactional
    public void delete(Long id) {
        Detection d = repo.findById(id).orElseThrow(() -> NotFoundException.of("detection", id));
        repo.deleteById(id);
        workflowEventDispatcher.onDetectionDeleted(id, d.getProjectId());
    }

    public List<com.martecyber.ares.detections.dto.DetectionAffectionDto> getAffections(Long detectionId) {
        Detection det = repo.findById(detectionId).orElseThrow(() -> NotFoundException.of("detection", detectionId));
        orgScope.assertProjectAccess(currentAuth(), det.getProjectId());
        assertClientDetectionAccess(currentAuth(), det.getProjectId());
        var affections = affectionRepo.findByDetectionId(detectionId);
        Map<Long, String> statusNames = findingStatusRepo.findAll().stream()
            .collect(Collectors.toMap(FindingStatus::getId, s -> s.getName() != null ? s.getName() : ""));
        return affections.stream().map(a -> {
            var finding = findingRepo.findById(a.getFindingId()).orElse(null);
            return new com.martecyber.ares.detections.dto.DetectionAffectionDto(
                a.getId(),
                a.getCode(),
                a.getTitle(),
                a.getDescription(),
                a.getStatus(),
                finding != null ? finding.getId() : null,
                finding != null ? finding.getCode() : null,
                finding != null ? finding.getTitle() : null,
                finding != null ? finding.getSeverity() : null,
                finding != null ? statusNames.getOrDefault(finding.getStatusId(), "") : null
            );
        }).toList();
    }

    public com.martecyber.ares.common.PagedResponse<com.martecyber.ares.detections.dto.DetectionStatusHistoryDto> getHistory(
            Long detectionId, int page, int size) {
        Detection det = repo.findById(detectionId).orElseThrow(() -> NotFoundException.of("detection", detectionId));
        orgScope.assertProjectAccess(currentAuth(), det.getProjectId());
        assertClientDetectionAccess(currentAuth(), det.getProjectId());
        var pageable = org.springframework.data.domain.PageRequest.of(
            Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = historyRepo.findByDetectionIdOrderByChangedAtDesc(detectionId, pageable)
            .map(h -> new com.martecyber.ares.detections.dto.DetectionStatusHistoryDto(
                h.getId(), h.getEventType(), h.getFromStatus(), h.getToStatus(),
                h.getChangedBy(), h.getChangedByName(), h.getNote(), h.getChangedAt()));
        return com.martecyber.ares.common.PagedResponse.of(result);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void recordHistory(Long detectionId, String eventType, String fromStatus, String toStatus,
                               Long changedBy, String changedByName, String note, OffsetDateTime changedAt) {
        historyRepo.save(new DetectionStatusHistory(
            detectionId, eventType, fromStatus, toStatus, changedBy, changedByName, note, changedAt));
        iterationStatsService.record(detectionId, toStatus, changedAt);
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private String currentUserName() {
        Long id = currentUserId();
        if (id == null) return null;
        return userRepo.findById(id).map(u -> u.getDisplayName()).orElse(null);
    }

    private Finding createFinding(EscalateDetectionRequest req, Detection detection) {
        return createFinding(req.projectId(), req.title(), req.severity(), detection.getTitle(), detection.getSeverity());
    }

    /** Shared by both {@link #escalate} and {@link #escalateBatch} — title/severity fall back to
     *  the escalating detection's own when left blank (for a batch, the first detection's). */
    private Finding createFinding(Long projectId, String requestedTitle, String requestedSeverity,
                                   String fallbackTitle, String fallbackSeverity) {
        FindingStatus openStatus = findingStatusRepo.findByName("open")
            .orElseThrow(() -> new IllegalStateException("Finding status 'open' not found"));

        long seq = findingRepo.countByProjectId(projectId) + 1;
        String code = "ENG-" + projectId + "-" + seq;

        Finding f = new Finding();
        f.setProjectId(projectId);
        f.setCode(code);
        f.setTitle(requestedTitle != null && !requestedTitle.isBlank() ? requestedTitle : fallbackTitle);
        String severity = requestedSeverity != null && !requestedSeverity.isBlank() ? requestedSeverity : fallbackSeverity;
        f.setSeverity(severity);
        f.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(severity));
        f.setStatusId(openStatus.getId());
        OffsetDateTime now = OffsetDateTime.now();
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        findingRepo.save(f);

        findingStatusHistoryRepo.save(new FindingStatusHistory(f.getId(), openStatus.getId(), now));

        return f;
    }

    private DetectionStatus resolveStatus(String name) {
        return statusRepo.findByName(name)
            .orElseThrow(() -> new IllegalArgumentException("Unknown detection status '" + name + "'"));
    }

    /** Validates current -> next against detection_status_transition and returns the resolved
     *  target status, so callers that need it (updateStatus) don't have to look it up twice. */
    private DetectionStatus validateTransition(String current, String next) {
        DetectionStatus currentStatus = resolveStatus(current);
        DetectionStatus nextStatus = resolveStatus(next);
        boolean allowed = statusTransitionRepo.findToStatusIdsByFromStatusId(currentStatus.getId())
            .contains(nextStatus.getId());
        if (!allowed) {
            throw new IllegalStateException(
                "Cannot transition detection status from '" + current + "' to '" + next + "'");
        }
        return nextStatus;
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s; }
}
