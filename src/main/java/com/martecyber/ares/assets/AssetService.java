package com.martecyber.ares.assets;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntry;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntryRepository;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssetService {

    private static final Map<String, String> PROJECT_SORT = Map.of(
        "identifier", "a.identifier",
        "type",       "a.type",
        "code",       "a.code",
        "createdAt",  "a.created_at",
        "scope",      "eaa.scope_status"
    );
    private static final Map<String, String> ORG_SORT = Map.of(
        "identifier", "a.identifier",
        "type",       "a.type",
        "code",       "a.code",
        "createdAt",  "a.created_at"
    );

    @PersistenceContext
    private EntityManager em;

    private final AssetRepository repo;
    private final AssetRelationshipService relSvc;
    private final AssetRelationshipRepository relRepo;
    private final OrganizationRepository orgRepo;
    private final AssetScopeClassifier classifier;
    private final ScopeClassifyScheduler scheduler;
    private final DetectionRepository detectionRepo;
    private final KbThirdPartyEntryRepository kbThirdPartyRepo;
    private final AssetTagRepository tagRepo;
    private final com.martecyber.ares.tags.TagRepository tagCatalogRepo;
    private final OrgScopeService orgScope;
    private final AssetAqlRegistry aqlRegistry;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander;

    public AssetService(AssetRepository repo, @Lazy AssetRelationshipService relSvc,
                        AssetRelationshipRepository relRepo,
                        OrganizationRepository orgRepo, @Lazy AssetScopeClassifier classifier,
                        @Lazy ScopeClassifyScheduler scheduler,
                        DetectionRepository detectionRepo,
                        KbThirdPartyEntryRepository kbThirdPartyRepo,
                        AssetTagRepository tagRepo,
                        com.martecyber.ares.tags.TagRepository tagCatalogRepo,
                        OrgScopeService orgScope,
                        AssetAqlRegistry aqlRegistry,
                        WorkflowEventDispatcher workflowEventDispatcher,
                        com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander) {
        this.repo = repo;
        this.relSvc = relSvc;
        this.relRepo = relRepo;
        this.orgRepo = orgRepo;
        this.classifier = classifier;
        this.scheduler = scheduler;
        this.detectionRepo = detectionRepo;
        this.kbThirdPartyRepo = kbThirdPartyRepo;
        this.tagRepo = tagRepo;
        this.tagCatalogRepo = tagCatalogRepo;
        this.orgScope = orgScope;
        this.aqlRegistry = aqlRegistry;
        this.workflowEventDispatcher = workflowEventDispatcher;
        this.aqlVariableExpander = aqlVariableExpander;
     }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** Assigns an existing tag to an asset — a platform tag (no organization of its own) or a
     *  tag belonging to the asset's own organization. */
    @Transactional
    public Asset assignTag(Long assetId, Long tagId) {
        Asset asset = get(assetId);
        com.martecyber.ares.tags.Tag tag = tagCatalogRepo.findById(tagId)
            .orElseThrow(() -> NotFoundException.of("tag", tagId));
        if (tag.getOrganizationId() != null && !tag.getOrganizationId().equals(asset.getOrganizationId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Tag belongs to a different organization");
        }
        tagRepo.assign(assetId, tagId);
        return get(assetId);
    }

    @Transactional
    public Asset unassignTag(Long assetId, Long tagId) {
        tagRepo.unassign(assetId, tagId);
        return get(assetId);
    }

    /** Batch-populates Asset.tags for a page of results — one query regardless of page size. */
    private void populateTags(List<Asset> assets) {
        if (assets.isEmpty()) return;
        List<Long> ids = assets.stream().map(Asset::getId).toList();
        Map<Long, List<com.martecyber.ares.tags.TagDto>> byAsset = new java.util.HashMap<>();
        for (AssetTagRepository.AssetTagRow row : tagRepo.findTagsForAssetIds(ids)) {
            byAsset.computeIfAbsent(row.getAssetId(), k -> new ArrayList<>())
                .add(new com.martecyber.ares.tags.TagDto(row.getId(), null, row.getName(), row.getColor()));
        }
        for (Asset a : assets) a.setTags(byAsset.getOrDefault(a.getId(), List.of()));
    }

    /** Aggregate visibility rule mirrors {@link ServiceVisibilityRepository#aggregateByAssetIds}
     *  exactly (OPEN only if every vantage point sees OPEN, CLOSED only if every one sees CLOSED,
     *  FILTERED otherwise) — expressed inline as a subquery so it composes with the other native
     *  SQL discrete filters below without a second round trip. */
    private static final String VISIBILITY_SUBQUERY =
        " a.id IN (" +
        "   SELECT service_asset_id FROM ares.service_visibility" +
        "   GROUP BY service_asset_id" +
        "   HAVING (CASE WHEN BOOL_AND(state = 'OPEN') THEN 'OPEN'" +
        "                WHEN BOOL_AND(state = 'CLOSED') THEN 'CLOSED'" +
        "                ELSE 'FILTERED' END) = ANY(string_to_array(:visibility, ','))" +
        " )";

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<Asset> list(Long organizationId, Long projectId, String type, String q,
                            List<String> scopeStatus, String protocol,
                            List<String> types, List<String> hostSubtypes, List<String> visibility,
                            String sortBy, String sortDir, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var auth = currentAuth();
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
            return listByProject(projectId, type, q, scopeStatus, protocol, types, hostSubtypes, visibility, sortBy, sortDir, p, s);
        }
        if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            return listByOrg(organizationId, type, q, protocol, types, hostSubtypes, visibility, sortBy, sortDir, p, s);
        }
        if (orgScope.isPlatformAdmin(auth)) {
            return listByOrg(null, type, q, protocol, types, hostSubtypes, visibility, sortBy, sortDir, p, s);
        }
        var accessible = orgScope.accessibleOrgIds(auth);
        if (accessible.isEmpty()) return new PageImpl<>(List.of(), PageRequest.of(p, s), 0);
        return listByOrgIds(List.copyOf(accessible), type, q, protocol, types, hostSubtypes, visibility, sortBy, sortDir, p, s);
    }

    @SuppressWarnings("unchecked")
    private Page<Asset> listByProject(Long projectId, String type, String q,
                                      List<String> scopeStatus, String protocol,
                                      List<String> types, List<String> hostSubtypes, List<String> visibility,
                                      String sortBy, String sortDir, int page, int size) {
        String col  = sortBy != null ? PROJECT_SORT.getOrDefault(sortBy, "a.created_at") : "a.created_at";
        String dir  = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String scope = (scopeStatus == null || scopeStatus.isEmpty()) ? null : String.join(",", scopeStatus);
        String typesCsv = (types == null || types.isEmpty()) ? null : String.join(",", types);
        String subtypesCsv = (hostSubtypes == null || hostSubtypes.isEmpty()) ? null : String.join(",", hostSubtypes);
        String visibilityCsv = (visibility == null || visibility.isEmpty()) ? null : String.join(",", visibility);

        String base =
            " FROM ares.asset a" +
            " JOIN ares.project_asset_access eaa ON eaa.asset_id = a.id" +
            " WHERE eaa.project_id = :projectId" +
            " AND (CAST(:type AS text) IS NULL OR a.type = :type)" +
            " AND (CAST(:q AS text) IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%')" +
            " AND (CAST(:scope AS text) IS NULL OR eaa.scope_status = ANY(string_to_array(:scope, ',')))" +
            " AND (CAST(:proto AS text) IS NULL OR LOWER(SPLIT_PART(a.identifier, '/', 2)) = ANY(string_to_array(:proto, ',')))" +
            " AND (CAST(:types AS text) IS NULL OR a.type = ANY(string_to_array(:types, ',')))" +
            " AND (CAST(:subtypes AS text) IS NULL OR a.host_subtype = ANY(string_to_array(:subtypes, ',')))" +
            " AND (CAST(:visibility AS text) IS NULL OR" + VISIBILITY_SUBQUERY + ")";

        var dataQ  = em.createNativeQuery("SELECT a.*" + base + " ORDER BY " + col + " " + dir, Asset.class);
        var countQ = em.createNativeQuery("SELECT COUNT(*)" + base);
        for (jakarta.persistence.Query nq : new jakarta.persistence.Query[]{ dataQ, countQ }) {
            nq.setParameter("projectId", projectId);
            nq.setParameter("type",  blank(type));
            nq.setParameter("q",     blank(q));
            nq.setParameter("scope", scope);
            nq.setParameter("proto", blank(protocol));
            nq.setParameter("types", typesCsv);
            nq.setParameter("subtypes", subtypesCsv);
            nq.setParameter("visibility", visibilityCsv);
        }
        dataQ.setFirstResult(page * size);
        dataQ.setMaxResults(size);

        List<Asset> content = dataQ.getResultList();
        long total = ((Number) countQ.getSingleResult()).longValue();
        populateTags(content);
        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    @SuppressWarnings("unchecked")
    private Page<Asset> listByOrg(Long organizationId, String type, String q, String protocol,
                                   List<String> types, List<String> hostSubtypes, List<String> visibility,
                                   String sortBy, String sortDir, int page, int size) {
        String col = sortBy != null ? ORG_SORT.getOrDefault(sortBy, "a.created_at") : "a.created_at";
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String typesCsv = (types == null || types.isEmpty()) ? null : String.join(",", types);
        String subtypesCsv = (hostSubtypes == null || hostSubtypes.isEmpty()) ? null : String.join(",", hostSubtypes);
        String visibilityCsv = (visibility == null || visibility.isEmpty()) ? null : String.join(",", visibility);

        String base =
            " FROM ares.asset a" +
            " WHERE (CAST(:orgId AS bigint) IS NULL OR a.organization_id = :orgId)" +
            " AND (CAST(:type AS text) IS NULL OR a.type = :type)" +
            " AND (CAST(:q AS text) IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%')" +
            " AND (CAST(:proto AS text) IS NULL OR LOWER(SPLIT_PART(a.identifier, '/', 2)) = ANY(string_to_array(:proto, ',')))" +
            " AND (CAST(:types AS text) IS NULL OR a.type = ANY(string_to_array(:types, ',')))" +
            " AND (CAST(:subtypes AS text) IS NULL OR a.host_subtype = ANY(string_to_array(:subtypes, ',')))" +
            " AND (CAST(:visibility AS text) IS NULL OR" + VISIBILITY_SUBQUERY + ")";

        var dataQ  = em.createNativeQuery("SELECT a.*" + base + " ORDER BY " + col + " " + dir, Asset.class);
        var countQ = em.createNativeQuery("SELECT COUNT(*)" + base);
        for (jakarta.persistence.Query nq : new jakarta.persistence.Query[]{ dataQ, countQ }) {
            nq.setParameter("orgId", organizationId);
            nq.setParameter("type",  blank(type));
            nq.setParameter("q",     blank(q));
            nq.setParameter("proto", blank(protocol));
            nq.setParameter("types", typesCsv);
            nq.setParameter("subtypes", subtypesCsv);
            nq.setParameter("visibility", visibilityCsv);
        }
        dataQ.setFirstResult(page * size);
        dataQ.setMaxResults(size);

        List<Asset> content = dataQ.getResultList();
        long total = ((Number) countQ.getSingleResult()).longValue();

        if (!content.isEmpty()) {
            List<KbThirdPartyEntry> kbEntries = kbThirdPartyRepo.findAllByEnabledTrue();
            if (!kbEntries.isEmpty()) {
                for (Asset a : content) a.setThirdParty(classifier.isThirdParty(a, kbEntries));
            }
        }
        populateTags(content);

        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    /** Same as {@link #listByOrg} but restricted to a caller-accessible set of orgs (non-admin, no explicit org filter). */
    @SuppressWarnings("unchecked")
    private Page<Asset> listByOrgIds(List<Long> organizationIds, String type, String q, String protocol,
                                      List<String> types, List<String> hostSubtypes, List<String> visibility,
                                      String sortBy, String sortDir, int page, int size) {
        String col = sortBy != null ? ORG_SORT.getOrDefault(sortBy, "a.created_at") : "a.created_at";
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String typesCsv = (types == null || types.isEmpty()) ? null : String.join(",", types);
        String subtypesCsv = (hostSubtypes == null || hostSubtypes.isEmpty()) ? null : String.join(",", hostSubtypes);
        String visibilityCsv = (visibility == null || visibility.isEmpty()) ? null : String.join(",", visibility);

        String base =
            " FROM ares.asset a" +
            " WHERE a.organization_id IN (:orgIds)" +
            " AND (CAST(:type AS text) IS NULL OR a.type = :type)" +
            " AND (CAST(:q AS text) IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%')" +
            " AND (CAST(:proto AS text) IS NULL OR LOWER(SPLIT_PART(a.identifier, '/', 2)) = ANY(string_to_array(:proto, ',')))" +
            " AND (CAST(:types AS text) IS NULL OR a.type = ANY(string_to_array(:types, ',')))" +
            " AND (CAST(:subtypes AS text) IS NULL OR a.host_subtype = ANY(string_to_array(:subtypes, ',')))" +
            " AND (CAST(:visibility AS text) IS NULL OR" + VISIBILITY_SUBQUERY + ")";

        var dataQ  = em.createNativeQuery("SELECT a.*" + base + " ORDER BY " + col + " " + dir, Asset.class);
        var countQ = em.createNativeQuery("SELECT COUNT(*)" + base);
        for (jakarta.persistence.Query nq : new jakarta.persistence.Query[]{ dataQ, countQ }) {
            nq.setParameter("orgIds", organizationIds);
            nq.setParameter("type",  blank(type));
            nq.setParameter("q",     blank(q));
            nq.setParameter("proto", blank(protocol));
            nq.setParameter("types", typesCsv);
            nq.setParameter("subtypes", subtypesCsv);
            nq.setParameter("visibility", visibilityCsv);
        }
        dataQ.setFirstResult(page * size);
        dataQ.setMaxResults(size);

        List<Asset> content = dataQ.getResultList();
        long total = ((Number) countQ.getSingleResult()).longValue();

        if (!content.isEmpty()) {
            List<KbThirdPartyEntry> kbEntries = kbThirdPartyRepo.findAllByEnabledTrue();
            if (!kbEntries.isEmpty()) {
                for (Asset a : content) a.setThirdParty(classifier.isThirdParty(a, kbEntries));
            }
        }
        populateTags(content);

        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    /**
     * AQL-driven listing (AQL implementation plan, Phase 3) — coexists with {@link #list} rather
     * than replacing it; when a caller supplies {@code aql} the API layer ignores the discrete
     * filter params entirely, mirroring Detection's and Finding's coexistence rule. Unlike those
     * two, Asset has no JPA-mapped association to Project (only the native {@code
     * project_asset_access} join table, mapped separately as {@link ProjectAssetAccess}), so the
     * project-scope predicate is a correlated EXISTS subquery instead of a theta join.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<Asset> listByAql(Long organizationId, Long projectId, String aql,
                                 String sortBy, String sortDir, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var auth = currentAuth();

        Long scopedOrgId = null;
        Set<Long> accessibleOrgIds = null;
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
        } else if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            scopedOrgId = organizationId;
        } else if (!orgScope.isPlatformAdmin(auth)) {
            accessibleOrgIds = orgScope.accessibleOrgIds(auth);
            if (accessibleOrgIds.isEmpty()) {
                return new PageImpl<>(List.of(), PageRequest.of(p, s), 0);
            }
        }

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Asset> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(assetScopeSpecification(projectId, scopedOrgId, accessibleOrgIds));

        // A Specification's predicate only actually runs once JPA builds the query inside
        // findAll() below — not at compile() above — so that's what needs to be wrapped for
        // AssetAqlProjectContext to be in place when the "scope" relation's correlation lambda
        // (see AssetAqlRegistry) actually reads it.
        Page<Asset> result = AssetAqlProjectContext.runWithProject(projectId,
            () -> repo.findAll(spec, PageRequest.of(p, s, buildAssetSort(sortBy, sortDir))));

        if (projectId == null && !result.getContent().isEmpty()) {
            List<KbThirdPartyEntry> kbEntries = kbThirdPartyRepo.findAllByEnabledTrue();
            if (!kbEntries.isEmpty()) {
                for (Asset a : result.getContent()) a.setThirdParty(classifier.isThirdParty(a, kbEntries));
            }
        }
        populateTags(result.getContent());
        return result;
    }

    /** Count-only variant of {@link #listByAql} — same scope resolution and AQL compilation, no
     *  paging/sort/tag-population, for Workflow CONDITION nodes comparing result counts instead of
     *  fetching rows. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public long countByAql(Long organizationId, Long projectId, String aql) {
        var auth = currentAuth();

        Long scopedOrgId = null;
        Set<Long> accessibleOrgIds = null;
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
        } else if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            scopedOrgId = organizationId;
        } else if (!orgScope.isPlatformAdmin(auth)) {
            accessibleOrgIds = orgScope.accessibleOrgIds(auth);
            if (accessibleOrgIds.isEmpty()) return 0;
        }

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Asset> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(assetScopeSpecification(projectId, scopedOrgId, accessibleOrgIds));
        return AssetAqlProjectContext.runWithProject(projectId, () -> repo.count(spec));
    }

    /** Grouped-count variant of {@link #countByAql} for the dashboard AQL_CHART widget — same
     *  scope resolution and AQL compilation, grouped by {@code groupByField} instead of a single
     *  total. See {@link com.martecyber.ares.aql.compile.AqlGroupCountSupport}. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> countGroupedByAql(
            Long organizationId, Long projectId, String aql, String groupByField, String dateBucket,
            String seriesField, Integer topN, String sortMode) {
        var auth = currentAuth();

        Long scopedOrgId = null;
        Set<Long> accessibleOrgIds = null;
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
        } else if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            scopedOrgId = organizationId;
        } else if (!orgScope.isPlatformAdmin(auth)) {
            accessibleOrgIds = orgScope.accessibleOrgIds(auth);
            if (accessibleOrgIds.isEmpty()) return List.of();
        }

        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aqlVariableExpander.expand(aql, projectId, organizationId));
        org.springframework.data.jpa.domain.Specification<Asset> spec =
            new com.martecyber.ares.aql.compile.PostgresSpecificationCompiler<>(aqlRegistry)
                .compile(node)
                .and(assetScopeSpecification(projectId, scopedOrgId, accessibleOrgIds));
        return AssetAqlProjectContext.runWithProject(projectId, () ->
            com.martecyber.ares.aql.compile.AqlGroupCountSupport.execute(
                em, spec, Asset.class, aqlRegistry, groupByField, dateBucket, seriesField, topN, sortMode));
    }

    private org.springframework.data.jpa.domain.Specification<Asset> assetScopeSpecification(
            Long projectId, Long organizationId, Set<Long> accessibleOrgIds) {
        return (root, query, cb) -> {
            if (projectId != null) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<com.martecyber.ares.projects.ProjectAssetAccess> paaRoot =
                    sub.from(com.martecyber.ares.projects.ProjectAssetAccess.class);
                sub.select(paaRoot.get("assetId"));
                sub.where(cb.and(
                    cb.equal(paaRoot.get("projectId"), projectId),
                    cb.equal(paaRoot.get("assetId"), root.get("id"))));
                return cb.exists(sub);
            }
            if (organizationId != null) {
                return cb.equal(root.get("organizationId"), organizationId);
            }
            if (accessibleOrgIds != null) {
                return root.get("organizationId").in(accessibleOrgIds);
            }
            return cb.conjunction();
        };
    }

    private org.springframework.data.domain.Sort buildAssetSort(String sortBy, String sortDir) {
        org.springframework.data.domain.Sort.Direction dir =
            "asc".equalsIgnoreCase(sortDir)
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;
        String col = switch (sortBy != null ? sortBy.toLowerCase() : "") {
            case "identifier" -> "identifier";
            case "type"       -> "type";
            case "code"       -> "code";
            default           -> "createdAt";
        };
        return org.springframework.data.domain.Sort.by(dir, col);
    }

    public Asset get(Long id) {
        Asset a = repo.findById(id).orElseThrow(() -> NotFoundException.of("asset", id));
        orgScope.assertOrgAccess(currentAuth(), a.getOrganizationId());
        populateTags(List.of(a));
        return a;
    }

    /** Bulk service → host traversal. Tuple shape: (serviceId, hostId, hostIdentifier, hostCode). */
    public List<Object[]> findHostsForServices(java.util.Collection<Long> serviceIds) {
        return repo.findHostsForServices(serviceIds);
    }

    /**
     * Generates a code in the format {org_slug}-{TYPE_CODE}-N.
     * N = count of existing assets of this type in the org + 1.
     * Public so AssetImportHelper can call it.
     */
    public String generateCode(Long organizationId, String type) {
        String slug = orgRepo.findById(organizationId)
            .map(o -> o.getSlug().toUpperCase())
            .orElse("ORG");
        String typeCode = AssetType.typeCode(type);
        long n = repo.maxCodeSequenceByOrgAndType(organizationId, type) + 1;
        return slug + "-" + typeCode + "-" + n;
    }

    @Transactional
    public Asset create(Long organizationId, String code, String type, String identifier, String metadata) {
        return create(organizationId, code, type, identifier, metadata, null);
    }

    @Transactional
    public Asset create(Long organizationId, String code, String type, String identifier, String metadata,
                        String hostSubtype) {
        return create(organizationId, code, type, identifier, metadata, hostSubtype, null);
    }

    @Transactional
    public Asset create(Long organizationId, String code, String type, String identifier, String metadata,
                        String hostSubtype, List<String> hostnames) {
        orgScope.assertOrgAccess(currentAuth(), organizationId);
        if (code == null || code.isBlank()) code = generateCode(organizationId, type);
        Asset a = new Asset();
        a.setOrganizationId(organizationId);
        a.setCode(code);
        a.setType(type);
        a.setIdentifier(identifier);
        a.setMetadata(metadata);
        if (AssetType.HOST.equals(type)) {
            a.setHostSubtype(hostSubtype != null && HostSubtype.ALL.contains(hostSubtype) ? hostSubtype : HostSubtype.UNKNOWN);
            // A manually-created host always has an explicit, required identifier — pin it,
            // same as directly editing Name does, so it's never silently overwritten by later
            // imports (mirrors the migration's safe-upgrade default for pre-existing hosts).
            a.setNameOverride(true);
            if (hostnames != null) a.setHostnames(dedupeHostnames(hostnames));
        }
        OffsetDateTime now = OffsetDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Asset saved = repo.save(a);

        // Auto-link networks/domains on creation
        if (AssetType.NETWORK.equals(type)) {
            autoLinkNetwork(organizationId, saved);
        } else if (AssetType.IP.equals(type)) {
            autoLinkIpToNetwork(organizationId, saved);
        } else if (AssetType.DOMAIN.equals(type)) {
            autoLinkSubdomain(organizationId, saved);
        }

        workflowEventDispatcher.onAssetCreated(saved);
        return saved;
    }

    @Transactional
    public Asset update(Long id, String type, String identifier, String metadata) {
        return update(id, type, identifier, metadata, null);
    }

    @Transactional
    public Asset update(Long id, String type, String identifier, String metadata, String hostSubtype) {
        return update(id, type, identifier, metadata, hostSubtype, null, null);
    }

    @Transactional
    public Asset update(Long id, String type, String identifier, String metadata, String hostSubtype,
                        List<String> hostnames, Boolean nameOverride) {
        Asset a = get(id);
        if (type != null) a.setType(type);
        if (identifier != null) {
            a.setIdentifier(identifier);
            // Editing the Name directly always pins it — auto-naming from hostnames stops
            // until the user explicitly resets it (nameOverride=false).
            if (AssetType.HOST.equals(a.getType())) a.setNameOverride(true);
        }
        if (metadata != null) a.setMetadata(metadata);
        if (hostSubtype != null && HostSubtype.ALL.contains(hostSubtype)) a.setHostSubtype(hostSubtype);
        if (nameOverride != null) a.setNameOverride(nameOverride);
        if (hostnames != null) a.setHostnames(dedupeHostnames(hostnames));
        // Recompute unconditionally (not just when hostnames changed) so resetting
        // nameOverride to false — with no other field in the same call — also takes
        // effect immediately; recomputeHostName() itself no-ops while still pinned.
        if (AssetType.HOST.equals(a.getType())) recomputeHostName(a);
        a.setUpdatedAt(OffsetDateTime.now());
        Asset saved = repo.save(a);
        scheduler.scheduleForAsset(id);
        workflowEventDispatcher.onAssetUpdated(saved);
        return saved;
    }

    /**
     * Recomputes a HOST asset's Name ({@code identifier}) from its {@code hostnames} list,
     * unless the user has pinned it ({@code nameOverride=true}). Auto-naming rule: first
     * hostname in the list, else a virtual "host-{ip}" fallback (a "host-{mac}" branch is
     * stubbed ahead of the IP fallback for when a MAC-keyed parser exists — unreachable today).
     */
    public void recomputeHostName(Asset host) {
        if (host.isNameOverride()) return;
        List<String> names = host.getHostnames();
        if (names != null && !names.isEmpty()) {
            host.setIdentifier(names.get(0));
            return;
        }
        String mac = null; // no MAC-keyed host resolution exists yet (network-capture parsing, future work)
        if (mac != null && !mac.isBlank()) {
            host.setIdentifier("host-" + mac);
            return;
        }
        String ip = findPrimaryIp(host.getId());
        host.setIdentifier(ip != null ? "host-" + ip : "host-unknown");
    }

    /** Resolves a HOST's primary IP via its HOST_INTERFACE -> INTERFACE_IP chain. */
    private String findPrimaryIp(Long hostId) {
        if (hostId == null) return null;
        return relRepo.findByFromAssetIdAndType(hostId, AssetLinkType.HOST_INTERFACE).stream()
            .findFirst()
            .flatMap(hostIface -> relRepo.findByFromAssetIdAndType(hostIface.getToAssetId(), AssetLinkType.INTERFACE_IP)
                .stream().findFirst())
            .flatMap(ifaceIp -> repo.findById(ifaceIp.getToAssetId()))
            .map(Asset::getIdentifier)
            .orElse(null);
    }

    /** Case-insensitive dedupe preserving the caller-supplied order (first occurrence wins). */
    private static List<String> dedupeHostnames(List<String> raw) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String h : raw) {
            if (h == null || h.isBlank()) continue;
            String trimmed = h.trim();
            if (seen.add(trimmed.toLowerCase())) result.add(trimmed);
        }
        return result;
    }

    @Transactional
    public void delete(Long id) {
        Asset a = repo.findById(id).orElseThrow(() -> NotFoundException.of("asset", id));
        detectionRepo.deleteByAssetIdIn(List.of(id));
        repo.deleteById(id);
        workflowEventDispatcher.onAssetDeleted(id, a.getOrganizationId());
    }

    @Transactional
    public void bulkDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        detectionRepo.deleteByAssetIdIn(ids);
        repo.deleteByIdIn(ids);
    }

    /** Returns assets that would become isolated (no remaining relationships) if {@code ids} were deleted. */
    public List<Object[]> previewOrphans(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return repo.findOrphansIfDeleted(ids);
    }

    // ── Network auto-linking ──────────────────────────────────────────────

    /**
     * When a Network asset is created, automatically:
     *  1. Link this network under its most specific existing ancestor network.
     *  2. Recompute, for every existing network/IP this one might now more specifically
     *     contain, whether it's actually a better (nearer) container than whatever they're
     *     linked to today — replacing a stale direct link when so.
     * <p>
     * Step 2 replaces the old "absorb direct subnets by prefix-length filter" approach, which
     * had two bugs: it never removed a candidate's existing NETWORK_SUBNET/NETWORK_IP link
     * before adding the new one (orphaned dual-parent links whenever a network arrived after
     * its would-be children), and its filter could skip over an already-correct, more-specific
     * intermediate network (linking a /24's grandchild directly to a newly-inserted /20,
     * bypassing an existing /26 that should stay in between). Recomputing each candidate's
     * true most-specific container from the full current network set (not just "direct"
     * matches) fixes both.
     */
    public void autoLinkNetwork(Long orgId, Asset network) {
        String cidr = network.getIdentifier();
        int prefix  = parsePrefixLen(cidr);
        if (prefix < 0) return; // not a valid CIDR

        List<Asset> allNetworks = repo.findByOrganizationIdAndType(orgId, AssetType.NETWORK);
        List<Asset> allIps      = repo.findByOrganizationIdAndType(orgId, AssetType.IP);

        // 1. Link to the nearest existing ancestor (highest prefix that still contains us).
        allNetworks.stream()
            .filter(n -> !n.getId().equals(network.getId()))
            .filter(n -> parsePrefixLen(n.getIdentifier()) < prefix)
            .filter(n -> isSubnetOf(cidr, n.getIdentifier()))
            .max(Comparator.comparingInt(n -> parsePrefixLen(n.getIdentifier())))
            .ifPresent(parent -> relSvc.linkIfAbsent(parent.getId(), network.getId(), AssetLinkType.NETWORK_SUBNET));

        // 2. Reparent any existing network/IP for which this one is now the true nearest container.
        for (Asset candidate : allNetworks) {
            if (candidate.getId().equals(network.getId())) continue;
            if (!isSubnetOf(candidate.getIdentifier(), cidr)) continue;
            reparentNetworkIfNearerContainer(candidate, allNetworks, network);
        }
        for (Asset ip : allIps) {
            String ipStr = extractIpStr(ip.getIdentifier());
            if (ipStr == null || !isIpInCidr(ipStr, cidr)) continue;
            reparentIpIfNearerContainer(ip, ipStr, allNetworks, network);
        }
    }

    /** Recomputes {@code candidate}'s true most-specific containing network among {@code pool}
     *  (which includes {@code newNetwork}); if that's {@code newNetwork} and differs from
     *  candidate's current NETWORK_SUBNET parent, deletes the stale link and creates the new one. */
    private void reparentNetworkIfNearerContainer(Asset candidate, List<Asset> pool, Asset newNetwork) {
        int candPrefix = parsePrefixLen(candidate.getIdentifier());
        java.util.Optional<Asset> best = pool.stream()
            .filter(n -> !n.getId().equals(candidate.getId()))
            .filter(n -> parsePrefixLen(n.getIdentifier()) < candPrefix)
            .filter(n -> isSubnetOf(candidate.getIdentifier(), n.getIdentifier()))
            .max(Comparator.comparingInt(n -> parsePrefixLen(n.getIdentifier())));
        if (best.isEmpty() || !best.get().getId().equals(newNetwork.getId())) return;
        relRepo.findByToAssetIdAndType(candidate.getId(), AssetLinkType.NETWORK_SUBNET).stream()
            .filter(r -> !r.getFromAssetId().equals(newNetwork.getId()))
            .forEach(r -> relSvc.delete(r.getFromAssetId(), candidate.getId(), AssetLinkType.NETWORK_SUBNET));
        relSvc.linkIfAbsent(newNetwork.getId(), candidate.getId(), AssetLinkType.NETWORK_SUBNET);
    }

    /** Same idea as {@link #reparentNetworkIfNearerContainer} for an IP's NETWORK_IP link. */
    private void reparentIpIfNearerContainer(Asset ip, String ipStr, List<Asset> pool, Asset newNetwork) {
        java.util.Optional<Asset> best = pool.stream()
            .filter(n -> isIpInCidr(ipStr, n.getIdentifier()))
            .max(Comparator.comparingInt(n -> parsePrefixLen(n.getIdentifier())));
        if (best.isEmpty() || !best.get().getId().equals(newNetwork.getId())) return;
        relRepo.findByToAssetIdAndType(ip.getId(), AssetLinkType.NETWORK_IP).stream()
            .filter(r -> !r.getFromAssetId().equals(newNetwork.getId()))
            .forEach(r -> relSvc.delete(r.getFromAssetId(), ip.getId(), AssetLinkType.NETWORK_IP));
        relSvc.linkIfAbsent(newNetwork.getId(), ip.getId(), AssetLinkType.NETWORK_IP);
    }

    /**
     * When an IP asset is created, link it to the most specific network that contains it.
     * No reparenting needed here — the IP is new, so it has no stale link to replace.
     */
    public void autoLinkIpToNetwork(Long orgId, Asset ip) {
        String ipStr = extractIpStr(ip.getIdentifier());
        if (ipStr == null) return;

        repo.findByOrganizationIdAndType(orgId, AssetType.NETWORK).stream()
            .filter(n -> isIpInCidr(ipStr, n.getIdentifier()))
            .max(Comparator.comparingInt(n -> parsePrefixLen(n.getIdentifier())))
            .ifPresent(best -> relSvc.linkIfAbsent(best.getId(), ip.getId(), AssetLinkType.NETWORK_IP));
    }

    /** One-time/repair recompute of the whole org's network+IP containment hierarchy — safe to
     *  re-run any time (every operation below is idempotent). Reprocesses networks broadest-first
     *  so each one sees the correct, already-converged state of its ancestors before candidate
     *  descendants are evaluated against it. */
    @Transactional
    public void recomputeNetworkTopology(Long orgId) {
        List<Asset> networks = repo.findByOrganizationIdAndType(orgId, AssetType.NETWORK);
        networks.sort(Comparator.comparingInt(n -> parsePrefixLen(n.getIdentifier())));
        for (Asset n : networks) autoLinkNetwork(orgId, n);
        for (Asset ip : repo.findByOrganizationIdAndType(orgId, AssetType.IP)) autoLinkIpToNetwork(orgId, ip);
    }

    // ── Domain auto-linking ─────────────────────────────────────────────────

    /**
     * When a Domain asset is created, automatically:
     *  1. Link it under its nearest existing ancestor domain (by label-suffix containment).
     *  2. Reparent any existing domain for which this one is now the true nearest ancestor —
     *     e.g. inserting {@code bar.example.com} between pre-existing {@code example.com} and
     *     {@code foo.bar.example.com} replaces the old direct link with two hops.
     * Mirrors {@link #autoLinkNetwork}'s algorithm, using label-suffix matching (a domain is
     * "contained by" a parent when it's a dot-boundary suffix of it) instead of CIDR containment.
     */
    public void autoLinkSubdomain(Long orgId, Asset domain) {
        String d = normalizeDomain(domain.getIdentifier());
        if (d == null || d.isBlank()) return;

        List<Asset> allDomains = repo.findByOrganizationIdAndType(orgId, AssetType.DOMAIN);

        allDomains.stream()
            .filter(n -> !n.getId().equals(domain.getId()))
            .filter(n -> isSubdomainOf(d, normalizeDomain(n.getIdentifier())))
            .max(Comparator.comparingInt(n -> labelCount(n.getIdentifier())))
            .ifPresent(parent -> relSvc.linkIfAbsent(parent.getId(), domain.getId(), AssetLinkType.DOMAIN_SUBDOMAIN));

        for (Asset candidate : allDomains) {
            if (candidate.getId().equals(domain.getId())) continue;
            String c = normalizeDomain(candidate.getIdentifier());
            if (c == null || !isSubdomainOf(c, d)) continue;
            reparentDomainIfNearerAncestor(candidate, allDomains, domain);
        }
    }

    private void reparentDomainIfNearerAncestor(Asset candidate, List<Asset> pool, Asset newDomain) {
        String c = normalizeDomain(candidate.getIdentifier());
        java.util.Optional<Asset> best = pool.stream()
            .filter(n -> !n.getId().equals(candidate.getId()))
            .filter(n -> isSubdomainOf(c, normalizeDomain(n.getIdentifier())))
            .max(Comparator.comparingInt(n -> labelCount(n.getIdentifier())));
        if (best.isEmpty() || !best.get().getId().equals(newDomain.getId())) return;
        relRepo.findByToAssetIdAndType(candidate.getId(), AssetLinkType.DOMAIN_SUBDOMAIN).stream()
            .filter(r -> !r.getFromAssetId().equals(newDomain.getId()))
            .forEach(r -> relSvc.delete(r.getFromAssetId(), candidate.getId(), AssetLinkType.DOMAIN_SUBDOMAIN));
        relSvc.linkIfAbsent(newDomain.getId(), candidate.getId(), AssetLinkType.DOMAIN_SUBDOMAIN);
    }

    private static String normalizeDomain(String s) {
        return s == null ? null : s.toLowerCase().trim();
    }

    private static int labelCount(String domain) {
        return domain == null ? 0 : domain.split("\\.").length;
    }

    /** True if {@code child} is a strict subdomain of {@code parent} — dot-boundary safe, so
     *  "evilexample.com" is never mistaken for a subdomain of "example.com". */
    static boolean isSubdomainOf(String child, String parent) {
        if (child == null || parent == null || child.equals(parent)) return false;
        return child.endsWith("." + parent);
    }

    /** One-time/repair recompute of the whole org's domain hierarchy, broadest (fewest labels)
     *  first — same rationale and idempotency guarantee as {@link #recomputeNetworkTopology}. */
    @Transactional
    public void recomputeDomainTopology(Long orgId) {
        List<Asset> domains = repo.findByOrganizationIdAndType(orgId, AssetType.DOMAIN);
        domains.sort(Comparator.comparingInt(d -> labelCount(d.getIdentifier())));
        for (Asset d : domains) autoLinkSubdomain(orgId, d);
    }

    // ── CIDR utilities ────────────────────────────────────────────────────

    /** Returns true if {@code ip} is within the CIDR block. Handles "ip" identifiers that may be just the IP string. */
    public static boolean isIpInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;
            int prefix = Integer.parseInt(parts[1].trim());
            if (prefix < 0 || prefix > 32) return false;
            long mask = prefix == 0 ? 0L : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
            return (ipToLong(parts[0]) & mask) == (ipToLong(ip) & mask);
        } catch (Exception e) { return false; }
    }

    /** Returns true if {@code subnet} is a subnet of {@code parent} (subnet has higher prefix, its base is within parent). */
    public static boolean isSubnetOf(String subnet, String parent) {
        int subPrefix    = parsePrefixLen(subnet);
        int parentPrefix = parsePrefixLen(parent);
        if (subPrefix <= parentPrefix) return false;
        String subBase = subnet.split("/")[0];
        return isIpInCidr(subBase, parent);
    }

    public static int parsePrefixLen(String cidr) {
        try { return Integer.parseInt(cidr.split("/")[1].trim()); }
        catch (Exception e) { return -1; }
    }

    private static long ipToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("Not IPv4: " + ip);
        long r = 0;
        for (String oct : o) r = (r << 8) | (Long.parseLong(oct) & 0xFF);
        return r;
    }

    /** Extract a bare IP from an identifier that might be "192.168.1.1" or "192.168.1.1:80/tcp". */
    private static String extractIpStr(String identifier) {
        if (identifier == null) return null;
        String raw = identifier.contains(":") ? identifier.split(":")[0] : identifier;
        return raw.matches("\\d{1,3}(\\.\\d{1,3}){3}") ? raw : null;
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
