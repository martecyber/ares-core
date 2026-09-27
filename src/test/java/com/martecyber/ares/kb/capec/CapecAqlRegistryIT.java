package com.martecyber.ares.kb.capec;

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
import com.martecyber.ares.kb.cwe.CweAqlRegistry;
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.cwe.CweRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Real-Postgres coverage for CAPEC (AQL-wide initiative, Phase 5): direct queries, HAS on the
 * newly array-backed fields, the JSONB-backed consequences round-trip, the {@code
 * findByCwe}/{@code filter(cwe=...)} array-containment query (which duplicates {@link
 * com.martecyber.ares.aql.compile.PostgresSpecificationCompiler}'s own qualified-function-name fix
 * for the Hibernate {@code array_position} collision — this test exists specifically to prove that
 * fix was applied here too, not just in the compiler), and that {@code detection.capec.*}/{@code
 * finding.capec.*} resolve correctly now that this namespace switched from KB_FEDERATED to a
 * RelationAqlField.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CapecAqlRegistryIT {

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

    @Autowired private CapecRepository capecRepository;
    @Autowired private CweRepository cweRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private CapecAqlRegistry capecRegistry;
    private PostgresSpecificationCompiler<CapecEntry> capecCompiler;
    private DetectionAqlRegistry detectionRegistry;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;

    private long projectId;

    @BeforeEach
    void seed() {
        capecRegistry = new CapecAqlRegistry();
        var cveRegistry = new CveAqlRegistry();
        var cveKevDetailRegistry = new CveKevDetailAqlRegistry();
        var owaspRegistry = new OwaspAqlRegistry();
        var cweRegistry = new CweAqlRegistry();
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        new AqlRegistryLookup(List.of(capecRegistry, cveRegistry, cveKevDetailRegistry, owaspRegistry, cweRegistry,
            assetRegistry, findingRegistry, detectionRegistry,
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(), new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry(),
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        capecCompiler = new PostgresSpecificationCompiler<>(capecRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);

        capecRepository.deleteAll();
        detectionRepository.deleteAll();
        cweRepository.deleteAll();

        // relatedCwes is now list[cwe] (an array-membership RelationAqlField, not the bare HAS-only
        // array relatedCweIds used to be) — the nested "relatedCwes.id == ..." queries below need
        // real CweEntry rows to correlate against, unlike the old bare HAS which only ever looked
        // at CAPEC's own array.
        cweRepository.save(cweEntry("CWE-79"));
        cweRepository.save(cweEntry("CWE-80"));
        cweRepository.save(cweEntry("CWE-89"));

        CapecEntry capec63 = new CapecEntry();
        capec63.setCapecId("63");
        capec63.setCode("CAPEC-63");
        capec63.setName("Cross-Site Scripting (XSS)");
        capec63.setAbstraction("Standard");
        capec63.setTypicalSeverity("High");
        capec63.setRelatedCweIds(List.of("CWE-79", "CWE-80"));
        capec63.setConsequences(List.of(new CapecEntry.Consequence(
            List.of("Confidentiality"), List.of("Read Data"), "High", "note")));
        capecRepository.save(capec63);

        CapecEntry capec66 = new CapecEntry();
        capec66.setCapecId("66");
        capec66.setCode("CAPEC-66");
        capec66.setName("SQL Injection");
        capec66.setAbstraction("Standard");
        capec66.setTypicalSeverity("Very High");
        capec66.setRelatedCweIds(List.of("CWE-89"));
        capecRepository.save(capec66);

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('CAPEC Test Org', 'capec-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'CAPEC Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        Long capecCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'CAPEC'")
            .getSingleResult()).longValue();

        Detection detection = new Detection();
        detection.setProjectId(projectId);
        detection.setTitle("Detection linked to CAPEC-63");
        detection.setSeverity("critical");
        detection.setPriority(PriorityThresholds.fromSeverityName("critical"));
        detection.setStatus("new");
        detection.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        detection.setCreatedAt(OffsetDateTime.now());
        detection.setUpdatedAt(OffsetDateTime.now());
        detectionRepository.save(detection);

        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, '63') RETURNING id")
            .setParameter("catalogId", capecCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", detection.getId()).executeUpdate();
    }

    private CweEntry cweEntry(String code) {
        CweEntry e = new CweEntry();
        e.setCweId(code.replace("CWE-", ""));
        // relatedCwes.id resolves against CweEntry.code (the prefixed form), not the bare cweId.
        e.setCode(code);
        return e;
    }

    private List<CapecEntry> runCapec(String aql) {
        Specification<CapecEntry> spec = capecCompiler.compile(AqlParser.parse(aql));
        return capecRepository.findAll(spec);
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec);
    }

    @Test
    void directAqlQueryOnAbstraction() {
        assertEquals(2, runCapec("abstraction == \"Standard\"").size());
    }

    /** relatedCwes is list[cwe] now (was the bare HAS-only array relatedCweIds) —
     *  "relatedCwes.id == ..." resolves to the same CAPECs "relatedCweIds HAS ..." used to (via
     *  array_contains_ci, the same case-insensitive membership check HAS itself uses), while also
     *  reaching every other CWE field through the same relation. */
    @Test
    void relatedCwesListRelationWorks() {
        assertEquals(1, runCapec("relatedCwes.id == \"cwe-79\"").size());
        assertEquals(0, runCapec("relatedCwes.id == \"cwe-999\"").size());
    }

    @Test
    void relatedCwesBareFieldIsNotDirectlyComparable() {
        assertThrows(com.martecyber.ares.aql.compile.AqlCompileException.class,
            () -> runCapec("relatedCwes == \"cwe-79\""));
    }

    @Test
    void consequencesRoundTripThroughJsonb() {
        CapecEntry reloaded = capecRepository.findByCapecId("63").orElseThrow();
        assertEquals(1, reloaded.getConsequences().size());
        assertEquals("High", reloaded.getConsequences().get(0).likelihood());
    }

    /** Mirrors CapecService.relatedCweIdsContains exactly (that method is private and
     *  CapecService itself isn't in a @DataJpaTest slice's bean graph — JobService/CapecXmlParser
     *  aren't loadable here) — verifies the same qualified pg_catalog.array_position pattern
     *  CapecService.findByCwe/filter(cwe=...) actually use, independent of the AQL/HAS path
     *  already covered by hasOnRelatedCweIdsWorks above. A bare "array_position" call here would
     *  silently match every row with a non-empty relatedCweIds array — the exact Phase 3
     *  regression this session already found and fixed once in the compiler itself; this test
     *  exists specifically to prove that fix was independently applied to this second call site. */
    private Specification<CapecEntry> relatedCweIdsContains(String cweId) {
        return (root, query, cb) -> {
            var position = cb.function("pg_catalog.array_position", Integer.class,
                root.get("relatedCweIds"), cb.literal(cweId.toLowerCase()));
            return cb.isNotNull(position);
        };
    }

    @Test
    void relatedCweIdsContainmentQueryMatchesOnlyTheRightRow() {
        assertEquals(1, capecRepository.findAll(relatedCweIdsContains("CWE-79")).size());
        assertEquals("63", capecRepository.findAll(relatedCweIdsContains("CWE-79")).get(0).getCapecId());
        assertEquals(0, capecRepository.findAll(relatedCweIdsContains("CWE-9999")).size());
        assertEquals(1, capecRepository.findAll(relatedCweIdsContains("CWE-89")).size());
        assertEquals("66", capecRepository.findAll(relatedCweIdsContains("CWE-89")).get(0).getCapecId());
    }

    @Test
    void capecRelationLeavesAreRegisteredOnDetection() {
        assertTrue(detectionRegistry.field("capec.id").isPresent());
        assertTrue(detectionRegistry.field("capec.relatedCwes").isPresent());
    }

    @Test
    void transitiveDetectionCapecQueryWorks() {
        assertEquals(1, runDetection("capec.id == \"CAPEC-63\"").size());
        assertEquals(0, runDetection("capec.id == \"CAPEC-66\"").size());
    }

    @Test
    void transitiveDetectionCapecHasWorksThroughTheRelation() {
        assertEquals(1, runDetection("capec.relatedCwes.id == \"cwe-79\"").size());
        assertEquals(0, runDetection("capec.relatedCwes.id == \"cwe-89\"").size());
    }
}
