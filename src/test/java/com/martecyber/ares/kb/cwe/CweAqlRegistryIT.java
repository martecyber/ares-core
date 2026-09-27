package com.martecyber.ares.kb.cwe;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.common.PriorityThresholds;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionAqlRegistry;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionStatusRepository;
import com.martecyber.ares.findings.FindingAqlRegistry;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry;
import com.martecyber.ares.kb.owasp.OwaspAqlRegistry;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Real-Postgres coverage for CWE (AQL-wide initiative, Phase 5): direct queries, HAS on
 * applicablePlatforms/observedExamples (none of which were HAS-queryable before this migration
 * since Mongo lists had no such operator), the {@code parents}/{@code children}/{@code
 * relatedCapecs} {@code list[X]} array-membership relations ({@code parents.id == ...}), and that
 * {@code detection.cwe.*}/{@code finding.cwe.*} resolve correctly now that this namespace switched
 * from KB_FEDERATED to a RelationAqlField.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CweAqlRegistryIT {

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

    @Autowired private CweRepository cweRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private CweAqlRegistry cweRegistry;
    private PostgresSpecificationCompiler<CweEntry> cweCompiler;
    private DetectionAqlRegistry detectionRegistry;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;

    private long projectId;

    @BeforeEach
    void seed() {
        cweRegistry = new CweAqlRegistry();
        var cveRegistry = new CveAqlRegistry();
        var cveKevDetailRegistry = new CveKevDetailAqlRegistry();
        var owaspRegistry = new OwaspAqlRegistry();
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        var capecRegistry = new com.martecyber.ares.kb.capec.CapecAqlRegistry();
        new AqlRegistryLookup(List.of(cweRegistry, cveRegistry, cveKevDetailRegistry, owaspRegistry, assetRegistry, findingRegistry, detectionRegistry, capecRegistry,
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(), new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry(),
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        cweCompiler = new PostgresSpecificationCompiler<>(cweRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);

        cweRepository.deleteAll();
        detectionRepository.deleteAll();

        // parents is list[cwe] now (an array-membership RelationAqlField, not a bare HAS array) —
        // the nested "parents.id == ..." query below needs a real CweEntry row with this ID
        // to correlate against, unlike the old bare HAS which only ever looked at each row's own array.
        CweEntry cwe74 = new CweEntry();
        cwe74.setCweId("74");
        cwe74.setCode("CWE-74");
        cwe74.setName("Injection");
        cwe74.setType("Category");
        cweRepository.save(cwe74);

        CweEntry cwe79 = new CweEntry();
        cwe79.setCweId("79");
        cwe79.setCode("CWE-79");
        cwe79.setName("Cross-site Scripting");
        cwe79.setType("Weakness");
        cwe79.setAbstraction("Base");
        cwe79.setParentIds(List.of("74"));
        cwe79.setApplicablePlatforms(List.of("PHP", "Web Server"));
        cweRepository.save(cwe79);

        CweEntry cwe89 = new CweEntry();
        cwe89.setCweId("89");
        cwe89.setCode("CWE-89");
        cwe89.setName("SQL Injection");
        cwe89.setType("Weakness");
        cwe89.setAbstraction("Base");
        cwe89.setParentIds(List.of("74"));
        cwe89.setApplicablePlatforms(List.of("SQL"));
        cweRepository.save(cwe89);

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('CWE Test Org', 'cwe-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'CWE Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        Long cweCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'CWE'")
            .getSingleResult()).longValue();

        Detection detection = new Detection();
        detection.setProjectId(projectId);
        detection.setTitle("Detection linked to CWE-79");
        detection.setSeverity("critical");
        detection.setPriority(PriorityThresholds.fromSeverityName("critical"));
        detection.setStatus("new");
        detection.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        detection.setCreatedAt(OffsetDateTime.now());
        detection.setUpdatedAt(OffsetDateTime.now());
        detectionRepository.save(detection);

        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, '79') RETURNING id")
            .setParameter("catalogId", cweCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", detection.getId()).executeUpdate();
    }

    private List<CweEntry> runCwe(String aql) {
        Specification<CweEntry> spec = cweCompiler.compile(AqlParser.parse(aql));
        return cweRepository.findAll(spec);
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec);
    }

    @Test
    void directAqlQueryOnType() {
        assertEquals(2, runCwe("type == \"Weakness\"").size());
    }

    /** parents is list[cwe] now (was the bare HAS-only array parentIds) — "parents.id == ..."
     *  resolves to the same CWEs "parentIds HAS ..." used to (via array_contains_ci), genuinely
     *  self-referential (CWE -> CWE) and unaffected by RelationExpansion's depth cap since this is
     *  only 1 hop deep. */
    @Test
    void parentsListRelationWorks() {
        // The correlation itself is still bare-cweId-to-bare-cweId (parentIds stores "74", matching
        // cwe74.cweId) — but the flattened leaf "parents.id" always resolves through CweEntry's own
        // "id" field, which is code-backed ("CWE-74"), same as every other id leaf in this codebase.
        assertEquals(2, runCwe("parents.id == \"CWE-74\"").size());
        assertEquals(0, runCwe("parents.id == \"CWE-999\"").size());
    }

    @Test
    void hasOnApplicablePlatformsIsCaseInsensitive() {
        assertEquals(1, runCwe("applicablePlatforms HAS \"php\"").size());
        assertEquals(1, runCwe("applicablePlatforms HAS \"PHP\"").size());
        assertEquals(1, runCwe("applicablePlatforms HAS \"sql\"").size());
    }

    @Test
    void cweRelationLeavesAreRegisteredOnDetection() {
        assertTrue(detectionRegistry.field("cwe.id").isPresent());
        assertTrue(detectionRegistry.field("cwe.name").isPresent());
        assertTrue(detectionRegistry.field("cwe.parents").isPresent());
    }

    @Test
    void transitiveDetectionCweQueryWorks() {
        assertEquals(1, runDetection("cwe.id == \"CWE-79\"").size());
        assertEquals(1, runDetection("cwe.name == \"Cross-site Scripting\"").size());
        assertEquals(0, runDetection("cwe.id == \"CWE-89\"").size());
    }

    @Test
    void transitiveDetectionCweHasWorksThroughTheRelation() {
        assertEquals(1, runDetection("cwe.applicablePlatforms HAS \"php\"").size());
        assertEquals(0, runDetection("cwe.applicablePlatforms HAS \"sql\"").size());
    }
}
