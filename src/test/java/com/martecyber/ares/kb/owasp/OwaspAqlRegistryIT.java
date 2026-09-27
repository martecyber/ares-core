package com.martecyber.ares.kb.owasp;

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
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.cwe.CweRepository;
import com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Real-Postgres coverage for OWASP (AQL-wide initiative, Phase 5 — first of the four remaining KB
 * catalogs migrated off MongoDB): the repository's upsert-by-(owaspId,year) shape, the AQL
 * registry's direct queries including HAS on {@code cwes}, and — critically — that {@code
 * detection.owasp.*} resolves correctly now that DetectionAqlRegistry/FindingAqlRegistry switched
 * this namespace from KB_FEDERATED to a RelationAqlField.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OwaspAqlRegistryIT {

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

    @Autowired private OwaspRepository owaspRepository;
    @Autowired private CweRepository cweRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private OwaspAqlRegistry owaspRegistry;
    private PostgresSpecificationCompiler<OwaspEntry> owaspCompiler;
    private DetectionAqlRegistry detectionRegistry;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;

    private long projectId;

    @BeforeEach
    void seed() {
        owaspRegistry = new OwaspAqlRegistry();
        var cveRegistry = new CveAqlRegistry();
        var cveKevDetailRegistry = new CveKevDetailAqlRegistry();
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        var cweRegistry = new com.martecyber.ares.kb.cwe.CweAqlRegistry();
        var capecRegistry = new com.martecyber.ares.kb.capec.CapecAqlRegistry();
        new AqlRegistryLookup(List.of(owaspRegistry, cveRegistry, cveKevDetailRegistry, assetRegistry, findingRegistry, detectionRegistry, cweRegistry, capecRegistry,
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(), new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry(),
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        owaspCompiler = new PostgresSpecificationCompiler<>(owaspRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);

        owaspRepository.deleteAll();
        detectionRepository.deleteAll();
        cweRepository.deleteAll();

        // cwes is list[cwe] now (an array-membership RelationAqlField, not a bare HAS array) — the
        // nested "cwes.id == ..." query below needs a real CweEntry row to correlate against.
        CweEntry cwe22 = new CweEntry();
        cwe22.setCweId("22");
        // cwes.id resolves against CweEntry.code (the prefixed form), not the bare cweId.
        cwe22.setCode("cwe-22");
        cweRepository.save(cwe22);

        OwaspEntry a01_2021 = new OwaspEntry();
        a01_2021.setOwaspId("A01");
        a01_2021.setYear(2021);
        a01_2021.setRank(1);
        a01_2021.setName("Broken Access Control");
        a01_2021.setCwes(List.of("CWE-22", "CWE-284"));
        owaspRepository.save(a01_2021);

        OwaspEntry a01_2017 = new OwaspEntry();
        a01_2017.setOwaspId("A01");
        a01_2017.setYear(2017);
        a01_2017.setRank(1);
        a01_2017.setName("Injection");
        owaspRepository.save(a01_2017);

        OwaspEntry a02_2021 = new OwaspEntry();
        a02_2021.setOwaspId("A02");
        a02_2021.setYear(2021);
        a02_2021.setRank(2);
        a02_2021.setName("Cryptographic Failures");
        owaspRepository.save(a02_2021);

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('OWASP Test Org', 'owasp-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'OWASP Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        Long owaspCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'OWASP'")
            .getSingleResult()).longValue();

        Detection detection = new Detection();
        detection.setProjectId(projectId);
        detection.setTitle("Detection linked to A01");
        detection.setSeverity("critical");
        detection.setPriority(PriorityThresholds.fromSeverityName("critical"));
        detection.setStatus("new");
        detection.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        detection.setCreatedAt(OffsetDateTime.now());
        detection.setUpdatedAt(OffsetDateTime.now());
        detectionRepository.save(detection);

        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, 'A01') RETURNING id")
            .setParameter("catalogId", owaspCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", detection.getId()).executeUpdate();
    }

    private List<OwaspEntry> runOwasp(String aql) {
        Specification<OwaspEntry> spec = owaspCompiler.compile(AqlParser.parse(aql));
        return owaspRepository.findAll(spec);
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec);
    }

    @Test
    void findByOwaspIdAndYearIsPrecise() {
        assertTrue(owaspRepository.findByOwaspIdAndYear("A01", 2021).isPresent());
        assertEquals("Broken Access Control", owaspRepository.findByOwaspIdAndYear("A01", 2021).get().getName());
        assertEquals("Injection", owaspRepository.findByOwaspIdAndYear("A01", 2017).get().getName());
    }

    @Test
    void findDistinctYearsReturnsSortedUniqueYears() {
        assertEquals(List.of(2017, 2021), owaspRepository.findDistinctYears());
    }

    @Test
    void findByYearOrderByRankAscWorks() {
        var results = owaspRepository.findByYearOrderByRankAsc(2021);
        assertEquals(2, results.size());
        assertEquals("A01", results.get(0).getOwaspId());
        assertEquals("A02", results.get(1).getOwaspId());
    }

    @Test
    void searchMatchesNameCaseInsensitively() {
        var results = owaspRepository.search("broken", Sort.by("rank"));
        assertEquals(1, results.size());
        assertEquals("A01", results.get(0).getOwaspId());
    }

    @Test
    void directAqlQueryOnYear() {
        assertEquals(2, runOwasp("year == 2021").size());
        assertEquals(1, runOwasp("year == 2017").size());
    }

    /** cwes is list[cwe] now, not a bare HAS-only array — "cwes.id == ..." resolves to the same
     *  OWASP categories "cwes HAS ..." used to, via array_contains_ci. */
    @Test
    void cwesListRelationWorks() {
        assertEquals(1, runOwasp("cwes.id == \"cwe-22\"").size());
        assertEquals(0, runOwasp("cwes.id == \"cwe-999\"").size());
    }

    @Test
    void owaspRelationLeavesAreRegisteredOnDetection() {
        assertTrue(detectionRegistry.field("owasp.id").isPresent());
        assertTrue(detectionRegistry.field("owasp.year").isPresent());
        assertTrue(detectionRegistry.field("owasp.rank").isPresent());
    }

    /** Correlates on owaspId alone (no year) — matches the exact same "any edition sharing that
     *  id" semantics the old KB_FEDERATED path had, so a detection referencing "A01" matches
     *  BOTH the 2021 and 2017 rows' conditions independently. */
    @Test
    void transitiveDetectionOwaspQueryMatchesAnyEditionSharingTheId() {
        assertEquals(1, runDetection("owasp.id == \"A01\"").size());
        assertEquals(1, runDetection("owasp.name == \"Injection\"").size());
        assertEquals(1, runDetection("owasp.name == \"Broken Access Control\"").size());
        assertEquals(0, runDetection("owasp.id == \"A02\"").size());
    }

    @Test
    void statsReflectsTotalAndYears() {
        assertEquals(3, owaspRepository.count());
    }
}
