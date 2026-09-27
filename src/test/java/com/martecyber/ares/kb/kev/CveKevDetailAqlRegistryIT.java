package com.martecyber.ares.kb.kev;

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
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.cwe.CweRepository;
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
 * Exercises {@code cve.kev.*} (AQL-wide initiative, Phase 4) against real Postgres — both directly
 * on CveAqlRegistry and, critically, transitively through {@code detection.cve.kev.*}. The latter
 * is the first genuinely two-hop relation chain in this codebase (Detection -&gt; cve -&gt; kev,
 * since CveAqlRegistry now has a relation of its own) and is exactly what {@link
 * AqlRegistryLookup}'s multi-round expansion fix (this same phase) exists to make work regardless
 * of registry construction order — see its own doc comment for why a single pass wasn't enough.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CveKevDetailAqlRegistryIT {

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

    @Autowired private CveRepository cveRepository;
    @Autowired private CweRepository cweRepository;
    @Autowired private CveKevDetailRepository kevRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private CveAqlRegistry cveRegistry;
    private PostgresSpecificationCompiler<CveEntry> cveCompiler;
    private DetectionAqlRegistry detectionRegistry;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;

    private long projectId;

    @BeforeEach
    void seed() {
        cveRegistry = new CveAqlRegistry();
        var kevRegistry = new CveKevDetailAqlRegistry();
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        var owaspRegistry = new com.martecyber.ares.kb.owasp.OwaspAqlRegistry();
        var cweRegistry = new com.martecyber.ares.kb.cwe.CweAqlRegistry();
        var capecRegistry = new com.martecyber.ares.kb.capec.CapecAqlRegistry();
        new AqlRegistryLookup(List.of(cveRegistry, kevRegistry, assetRegistry, findingRegistry, detectionRegistry, owaspRegistry, cweRegistry, capecRegistry,
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(), new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry(),
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        cveCompiler = new PostgresSpecificationCompiler<>(cveRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);

        kevRepository.deleteAll();
        detectionRepository.deleteAll();
        cveRepository.deleteAll();
        cweRepository.deleteAll();

        // kev.cwes is list[cwe] now (an array-membership RelationAqlField, not a bare HAS array) —
        // the nested "kev.cwes.id == ..." query below needs a real CweEntry row to correlate against.
        CweEntry cwe79 = new CweEntry();
        cwe79.setCweId("79");
        // kev.cwes.id resolves against CweEntry.code (the prefixed form), not the bare cweId.
        cwe79.setCode("cwe-79");
        cweRepository.save(cwe79);

        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2099-1111");
        cve.setSeverity("critical");
        cveRepository.save(cve);

        CveEntry unlisted = new CveEntry();
        unlisted.setCveId("CVE-2099-2222");
        unlisted.setSeverity("high");
        cveRepository.save(unlisted);

        CveKevDetail cisaRow = new CveKevDetail();
        cisaRow.setCveId("CVE-2099-1111");
        cisaRow.setSource("cisa");
        cisaRow.setKnownRansomwareCampaignUse(true);
        cisaRow.setVulnerabilityName("Test Vuln (CISA)");
        kevRepository.save(cisaRow);

        CveKevDetail vulncheckRow = new CveKevDetail();
        vulncheckRow.setCveId("CVE-2099-1111");
        vulncheckRow.setSource("vulncheck");
        vulncheckRow.setReportedExploitedByCanaries(true);
        vulncheckRow.setCwes(List.of("CWE-79"));
        kevRepository.save(vulncheckRow);

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('KEV Test Org', 'kev-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'KEV Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        Long cveCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'CVE'")
            .getSingleResult()).longValue();

        Detection detection = new Detection();
        detection.setProjectId(projectId);
        detection.setTitle("Detection linked to CVE-2099-1111");
        detection.setSeverity("critical");
        detection.setPriority(PriorityThresholds.fromSeverityName("critical"));
        detection.setStatus("new");
        detection.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        detection.setCreatedAt(OffsetDateTime.now());
        detection.setUpdatedAt(OffsetDateTime.now());
        detectionRepository.save(detection);

        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, 'CVE-2099-1111') RETURNING id")
            .setParameter("catalogId", cveCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", detection.getId()).executeUpdate();
    }

    private List<CveEntry> runCve(String aql) {
        Specification<CveEntry> spec = cveCompiler.compile(AqlParser.parse(aql));
        return cveRepository.findAll(spec);
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec);
    }

    @Test
    void kevRelationLeavesAreRegisteredOnCve() {
        assertTrue(cveRegistry.field("kev.source").isPresent());
        assertTrue(cveRegistry.field("kev.knownRansomwareCampaignUse").isPresent());
        assertTrue(cveRegistry.field("kev.cwes").isPresent());
    }

    @Test
    void directCveKevSourceQueryMatchesTheRightCve() {
        assertEquals(1, runCve("kev.source == \"cisa\"").size());
        assertEquals("CVE-2099-1111", runCve("kev.source == \"cisa\"").get(0).getCveId());
        assertEquals(0, runCve("kev.source == \"cisa\" AND id == \"CVE-2099-2222\"").size());
    }

    @Test
    void bothKevRowsForTheSameCveAreIndependentlyMatchable() {
        // One CVE, two kev rows (cisa + vulncheck) — each condition can be satisfied by a
        // DIFFERENT row, same documented caveat as every other RelationAqlField in this codebase.
        assertEquals(1, runCve("kev.knownRansomwareCampaignUse == true").size());
        assertEquals(1, runCve("kev.reportedExploitedByCanaries == true").size());
    }

    /** kev.cwes is list[cwe] now, not a bare HAS-only array — "kev.cwes.id == ..." resolves to
     *  the same CVEs "kev.cwes HAS ..." used to, via array_contains_ci. Three hops deep from
     *  Detection's own perspective (detection.cve.kev.cwes.id) — within RelationExpansion's
     *  depth cap. */
    @Test
    void kevCwesListRelationWorksThroughTheRelation() {
        assertEquals(1, runCve("kev.cwes.id == \"cwe-79\"").size());
        assertEquals(0, runCve("kev.cwes.id == \"cwe-999\"").size());
    }

    /** The critical regression test for this phase's AqlRegistryLookup fix: without the
     *  multi-round expansion pass, this three-level chain (Detection -> cve -> kev) would throw
     *  AqlFieldNotFoundException for "cve.kev.source" whenever DetectionAqlRegistry happened to
     *  expand its own "cve.*" relation before CveAqlRegistry had expanded its "kev.*" one. */
    @Test
    void transitiveDetectionCveKevSourceQueryWorks() {
        assertTrue(detectionRegistry.field("cve.kev.source").isPresent());
        List<Detection> matches = runDetection("cve.kev.source == \"cisa\"");
        assertEquals(1, matches.size());
        assertEquals("Detection linked to CVE-2099-1111", matches.get(0).getTitle());
    }

    @Test
    void transitiveQueryFindsNothingForAnUnrelatedSource() {
        assertEquals(0, runDetection("cve.kev.source == \"unknown-source\"").size());
    }
}
