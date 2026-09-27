package com.martecyber.ares.aql.compile;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.AqlFieldNotFoundException;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.aql.registry.FieldDefinitionRepository;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionAqlRegistry;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionStatusRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingAqlRegistry;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.assets.AssetAqlProjectContext;
import com.martecyber.ares.assets.AssetTagRepository;
import com.martecyber.ares.projects.ProjectAssetAccess;
import com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import com.martecyber.ares.tags.Tag;
import com.martecyber.ares.tags.TagRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises {@code AqlFieldKind.RELATION} (AQL-wide initiative, Phase 2) end-to-end against real
 * Postgres — {@code detection.asset.*} (one-hop scalar FK, Detection -&gt; Asset), {@code
 * finding.detections.*}/{@code detection.findings.*} (two-hop via Affection.detections), and the
 * role-separated {@code finding.affectedAssets.*}/{@code finding.detectedAtAssets.*} (two-hop via
 * Affection→AffectionAsset). Deliberately builds registries + a real {@link AqlRegistryLookup}
 * (not each registry standalone, unlike the sibling Detection/Asset/FindingTemplate compiler ITs)
 * — the RELATION flat-expansion pass only runs inside {@code AqlRegistryLookup}'s own constructor,
 * mirroring exactly how Spring wires it in production.
 *
 * <p>The REVERSE direction (Asset -&gt; Detection/Finding: {@code asset.detections.*}/{@code
 * asset.affectedByFindings.*}/{@code asset.detectedAtFindings.*}) used to exist too and was
 * removed as a security fix (user-reported): Asset is organization-scoped only (no {@code
 * project_id} — the same asset row can legitimately be referenced by Detections/Findings from
 * several different projects within the org), while Detection/Finding are strictly project-scoped;
 * the relation had no project filter at all, so it leaked cross-project detection/finding
 * existence through a shared asset, and was reachable from the organization-level Assets AQL bar
 * where NO detection data should ever be visible. {@link #assetHasNoReverseDetectionOrFindingRelations}
 * is the regression test for that fix — every OTHER test in this class exercises directions that
 * were never affected (Detection/Finding are always queried already scoped to their own project).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostgresSpecificationCompilerRelationIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        int port = Integer.getInteger("AQL_IT_PG_PORT", 15432);
        String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/ares";
        registry.add("spring.datasource.url", () -> jdbcUrl + "?currentSchema=ares");
        registry.add("spring.datasource.username", () -> "ares");
        registry.add("spring.datasource.password", () -> "ares");
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> "ares");
        registry.add("spring.flyway.password", () -> "ares");
    }

    @Autowired private AssetRepository assetRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private FindingStatusRepository findingStatusRepository;
    @Autowired private AffectionRepository affectionRepository;
    @Autowired private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private AssetTagRepository assetTagRepository;
    @Autowired private ProjectAssetAccessRepository projectAssetAccessRepository;

    @PersistenceContext
    private EntityManager em;

    private AssetAqlRegistry assetRegistry;
    private DetectionAqlRegistry detectionRegistry;
    private FindingAqlRegistry findingRegistry;
    private PostgresSpecificationCompiler<Asset> assetCompiler;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;
    private PostgresSpecificationCompiler<Finding> findingCompiler;

    private long organizationId;
    private long projectId;
    private Asset assetDetectedAt;
    private Asset assetAffects;
    private Asset assetTaggedBoth;
    private Asset assetTaggedOtherOnly;
    private Asset assetTaggedNone;
    private Detection detectionOnAssetDetectedAt;
    private Finding finding;

    @BeforeEach
    void seed() {
        assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        // Same mechanism Spring uses in production (AqlRegistryLookup's own constructor calls
        // expandRelations on every registry it's given) — must go through this, not use the
        // registries standalone, or the "<relation>.<leaf>" flat entries never get built.
        // CveAqlRegistry has to be in this list too even though this test never queries cve.* —
        // Detection/Finding both also register a "cve" RelationAqlField (Phase 3), and the real
        // Postgres this IT runs against has the CVE catalog seeded, so expandRelations would try
        // (and fail) to resolve it without CveAqlRegistry present in the lookup.
        new AqlRegistryLookup(List.of(assetRegistry, detectionRegistry, findingRegistry,
            new com.martecyber.ares.tags.TagAqlRegistry(),
            new ProjectAssetAccessAqlRegistry(),
            new com.martecyber.ares.kb.cve.CveAqlRegistry(),
            new com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry(),
            new com.martecyber.ares.kb.owasp.OwaspAqlRegistry(),
            new com.martecyber.ares.kb.cwe.CweAqlRegistry(),
            new com.martecyber.ares.kb.capec.CapecAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry()));
        assetCompiler = new PostgresSpecificationCompiler<>(assetRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);
        findingCompiler = new PostgresSpecificationCompiler<>(findingRegistry);

        affectionRepository.deleteAll();
        detectionRepository.deleteAll();
        findingRepository.deleteAll();
        assetRepository.deleteAll();

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('AQL Relation Test Org', 'aql-rel-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        this.organizationId = orgId.longValue();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'AQL Relation Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        assetDetectedAt = assetRepository.save(asset("web01.internal"));
        assetAffects = assetRepository.save(asset("db01.internal"));
        Asset unrelatedAsset = assetRepository.save(asset("unrelated.internal"));

        // tags.name != X vs NOT (tags.name == X) regression fixture — see the two tests below.
        // assetTaggedBoth carries the "excluded" tag AND another one: the exact shape that
        // previously distinguished the two forms (a naive EXISTS(tag != X) would wrongly match it,
        // since it DOES have some other tag, even though it also has X).
        Tag scannedTag = tagRepository.save(tag("nuclei_easm_scanned"));
        Tag otherTag = tagRepository.save(tag("other_tag"));
        assetTaggedBoth = assetRepository.save(asset("roe-tag-test-both.internal"));
        assetTaggedOtherOnly = assetRepository.save(asset("roe-tag-test-other-only.internal"));
        assetTaggedNone = assetRepository.save(asset("roe-tag-test-none.internal"));
        assetTagRepository.assign(assetTaggedBoth.getId(), scannedTag.getId());
        assetTagRepository.assign(assetTaggedBoth.getId(), otherTag.getId());
        assetTagRepository.assign(assetTaggedOtherOnly.getId(), otherTag.getId());

        // scope.* fixture — assetDetectedAt is in scope (no override), assetAffects is out of
        // scope via a manual override.
        ProjectAssetAccess inScope = new ProjectAssetAccess();
        inScope.setProjectId(projectId);
        inScope.setAssetId(assetDetectedAt.getId());
        inScope.setScopeStatus("in_scope");
        inScope.setScopeOverride(false);
        projectAssetAccessRepository.save(inScope);

        ProjectAssetAccess outOfScopeOverride = new ProjectAssetAccess();
        outOfScopeOverride.setProjectId(projectId);
        outOfScopeOverride.setAssetId(assetAffects.getId());
        outOfScopeOverride.setScopeStatus("out_of_scope");
        outOfScopeOverride.setScopeOverride(true);
        projectAssetAccessRepository.save(outOfScopeOverride);

        detectionOnAssetDetectedAt = detectionRepository.save(detection("SQL Injection", "P0", assetDetectedAt.getId()));
        Detection unrelatedDetection = detectionRepository.save(detection("Unrelated finding-less detection", "P4", unrelatedAsset.getId()));

        finding = findingRepository.save(finding("Critical SQLi in login"));

        Affection affection = new Affection();
        affection.setFindingId(finding.getId());
        affection.setCode("AQL-REL-" + System.nanoTime());
        affection.setCreatedAt(OffsetDateTime.now());
        affection.setUpdatedAt(OffsetDateTime.now());
        affection.getDetections().add(detectionOnAssetDetectedAt);
        affection = affectionRepository.save(affection);
        // addAssetLink needs the Affection's own id already assigned — hence the two-step save.
        affection.addAssetLink(assetDetectedAt.getId(), "detected_at", OffsetDateTime.now());
        affection.addAssetLink(assetAffects.getId(), "affects", OffsetDateTime.now());
        affectionRepository.save(affection);

        em.flush();
        em.clear();
    }

    private Asset asset(String identifier) {
        Asset a = new Asset();
        a.setOrganizationId(organizationId);
        a.setCode(identifier.toUpperCase() + "-" + System.nanoTime());
        a.setType("host");
        a.setIdentifier(identifier);
        a.setMetadata("{}");
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return a;
    }

    private Detection detection(String title, String priorityLabel, Long assetId) {
        Detection d = new Detection();
        d.setProjectId(projectId);
        d.setAssetId(assetId);
        d.setTitle(title);
        d.setSeverity("critical");
        d.setPriority(Short.parseShort(priorityLabel.substring(1))); // "P0" -> 0, "P4" -> 4
        d.setStatus("new");
        d.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        d.setOccurrenceCount(1);
        d.setCreatedAt(OffsetDateTime.now());
        d.setUpdatedAt(OffsetDateTime.now());
        return d;
    }

    private Tag tag(String name) {
        Tag t = new Tag();
        t.setOrganizationId(organizationId);
        t.setName(name);
        t.setColor("#4F46E5");
        t.setCreatedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        return t;
    }

    private Finding finding(String title) {
        Finding f = new Finding();
        f.setProjectId(projectId);
        f.setTitle(title);
        f.setStatusId(findingStatusRepository.findAll().stream().findFirst().orElseThrow().getId());
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());
        return f;
    }

    private List<Asset> runAsset(String aql) {
        Specification<Asset> spec = assetCompiler.compile(AqlParser.parse(aql));
        return assetRepository.findAll(spec).stream().sorted(Comparator.comparing(Asset::getIdentifier)).toList();
    }

    /** Mirrors AssetService.listByAql/countByAql's own AssetAqlProjectContext wrapping — a
     *  Specification's predicate only actually runs once findAll() builds the query, not at
     *  compile() time, so the context has to be in place around the repository call itself. */
    private List<Asset> runAssetInProject(String aql, Long projectId) {
        Specification<Asset> spec = assetCompiler.compile(AqlParser.parse(aql));
        return AssetAqlProjectContext.runWithProject(projectId, () ->
            assetRepository.findAll(spec).stream().sorted(Comparator.comparing(Asset::getIdentifier)).toList());
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec).stream().sorted(Comparator.comparing(Detection::getTitle)).toList();
    }

    private List<Finding> runFinding(String aql) {
        Specification<Finding> spec = findingCompiler.compile(AqlParser.parse(aql));
        return findingRepository.findAll(spec).stream().sorted(Comparator.comparing(Finding::getTitle)).toList();
    }

    /** Security regression test — see this class's own doc comment. Asset must never expose a way
     *  to reach Detection/Finding data, in either its bare relation form or any flattened leaf, at
     *  compile time — an org-level Assets AQL query has no business surfacing project-scoped
     *  detection/finding data at all, and even at project scope the same asset can be linked to a
     *  DIFFERENT project's detections, which must never leak through here either. */
    @Test
    void assetHasNoReverseDetectionOrFindingRelations() {
        assertTrue(assetRegistry.field("detections").isEmpty());
        assertTrue(assetRegistry.field("affectedByFindings").isEmpty());
        assertTrue(assetRegistry.field("detectedAtFindings").isEmpty());
        assertThrows(AqlFieldNotFoundException.class, () -> runAsset("detections.title == \"SQL Injection\""));
        assertThrows(AqlFieldNotFoundException.class, () -> runAsset("affectedByFindings.title == \"x\""));
        assertThrows(AqlFieldNotFoundException.class, () -> runAsset("detectedAtFindings.title == \"x\""));
    }

    @Test
    void detectionAssetOneHopForwardRelation() {
        List<Detection> results = runDetection("asset.identifier == \"web01.internal\"");
        assertEquals(1, results.size());
        assertEquals("SQL Injection", results.get(0).getTitle());
    }

    @Test
    void findingDetectionsTwoHopViaAffection() {
        List<Finding> results = runFinding("detections.title == \"SQL Injection\"");
        assertEquals(1, results.size());
        assertEquals("Critical SQLi in login", results.get(0).getTitle());
    }

    @Test
    void detectionFindingsReverseOfTwoHop() {
        List<Detection> results = runDetection("findings.title == \"Critical SQLi in login\"");
        assertEquals(1, results.size());
        assertEquals("SQL Injection", results.get(0).getTitle());

        // The detection with no Affection linkage at all must not spuriously match.
        assertEquals(0, runDetection("title == \"Unrelated finding-less detection\" AND findings.title == \"Critical SQLi in login\"").size());
    }

    @Test
    void findingDetectedAtAssetsMatchesOnlyTheDetectedAtRole() {
        assertEquals(1, runFinding("detectedAtAssets.identifier == \"web01.internal\"").size());
        // db01 is linked with role='affects', not 'detected_at' — must NOT leak across roles.
        assertEquals(0, runFinding("detectedAtAssets.identifier == \"db01.internal\"").size());
    }

    @Test
    void findingAffectedAssetsMatchesOnlyTheAffectsRole() {
        assertEquals(1, runFinding("affectedAssets.identifier == \"db01.internal\"").size());
        assertEquals(0, runFinding("affectedAssets.identifier == \"web01.internal\"").size());
    }

    @Test
    void relationLeafComposesWithInOperator() {
        assertEquals(1, runFinding("detections.priority IN [P0,P1]").size());
        assertEquals(0, runFinding("title == \"Critical SQLi in login\" AND detections.priority IN [P3,P4]").size());
    }

    @Test
    void notNegatesARelationLeafComparison() {
        List<Detection> results = runDetection("NOT (asset.identifier == \"web01.internal\")");
        assertTrue(results.stream().noneMatch(d -> "SQL Injection".equals(d.getTitle())));
        assertTrue(results.stream().anyMatch(d -> "Unrelated finding-less detection".equals(d.getTitle())));
    }

    /** Regression test for a real user-reported bug: {@code tags.name != "X"} and {@code NOT
     *  (tags.name == "X")} must mean the same thing — "no tag of this asset is named X" — not
     *  "this asset has some tag that isn't X" (true for {@link #assetTaggedBoth}, which has "X"
     *  AND another tag, even though it's clearly supposed to be excluded). The old (buggy)
     *  compilation pushed NEQ's negation into the correlated subquery's own WHERE clause instead of
     *  negating the whole EXISTS, which is exactly what made {@link #assetTaggedBoth} wrongly
     *  match. */
    @Test
    void tagsNameNotEqualsMatchesTheSameAssetsAsNotEqualsComparison() {
        List<Asset> viaNeq = runAsset("identifier ~= \"roe-tag-test\" AND tags.name != \"nuclei_easm_scanned\"");
        List<Asset> viaNot = runAsset("identifier ~= \"roe-tag-test\" AND NOT (tags.name == \"nuclei_easm_scanned\")");

        List<String> expected = List.of("roe-tag-test-none.internal", "roe-tag-test-other-only.internal");
        assertEquals(expected, viaNeq.stream().map(Asset::getIdentifier).toList());
        assertEquals(expected, viaNot.stream().map(Asset::getIdentifier).toList());

        // The asset that carries BOTH tags must be excluded by both forms — this is the specific
        // case the old buggy != compilation got wrong.
        assertTrue(viaNeq.stream().noneMatch(a -> a.getId().equals(assetTaggedBoth.getId())));
        assertTrue(viaNot.stream().noneMatch(a -> a.getId().equals(assetTaggedBoth.getId())));
    }

    @Test
    void tagsNameEqualsStillMatchesOnlyAssetsCarryingThatTag() {
        List<Asset> results = runAsset("identifier ~= \"roe-tag-test\" AND tags.name == \"nuclei_easm_scanned\"");
        assertEquals(List.of(assetTaggedBoth.getId()), results.stream().map(Asset::getId).toList());
    }

    @Test
    void scopeStatusAndOverrideFilterAssetsWithinAProject() {
        List<Asset> inScope = runAssetInProject("scope.status == \"in_scope\"", projectId);
        assertEquals(List.of(assetDetectedAt.getId()), inScope.stream().map(Asset::getId).toList());

        List<Asset> overridden = runAssetInProject("scope.override == true", projectId);
        assertEquals(List.of(assetAffects.getId()), overridden.stream().map(Asset::getId).toList());

        List<Asset> outOfScope = runAssetInProject("scope.status == \"out_of_scope\"", projectId);
        assertEquals(List.of(assetAffects.getId()), outOfScope.stream().map(Asset::getId).toList());
    }

    /** No project_asset_access row exists for the unrelated asset — same EXISTS semantics as every
     *  other relation, not a wrong/missing-scope-means-in-scope default. */
    @Test
    void scopeStatusExcludesAssetsWithNoAccessRowForThatProject() {
        List<Asset> inScope = runAssetInProject("scope.status == \"in_scope\"", projectId);
        assertTrue(inScope.stream().noneMatch(a -> "unrelated.internal".equals(a.getIdentifier())));
    }

    /** Regression guard for the whole point of {@code AssetAqlProjectContext}: querying scope.*
     *  outside a project-scoped call (the org-wide Assets view) must fail clearly, not silently
     *  match nothing or everything. */
    @Test
    void scopeFieldThrowsWhenQueriedOutsideAProject() {
        var ex = assertThrows(AqlCompileException.class, () -> runAsset("scope.status == \"in_scope\""));
        assertTrue(ex.getMessage().contains("project"));
    }

    @Test
    void unknownNestedFieldIsRejected() {
        assertThrows(AqlFieldNotFoundException.class, () -> runFinding("detections.notAField == foo"));
    }

    @Test
    void bareRelationNameWithoutALeafIsRejected() {
        var ex = assertThrows(AqlCompileException.class, () -> runFinding("detections == foo"));
        assertTrue(ex.getMessage().contains("does not support operator"));
    }
}
