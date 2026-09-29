package com.martecyber.ares.kb.attack;

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
import com.martecyber.ares.kb.capec.CapecAqlRegistry;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.kb.cwe.CweAqlRegistry;
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
 * Real-Postgres coverage for ATT&CK (AQL-wide initiative, Phase 5 — final entity of this phase):
 * direct technique queries, the new {@code attack_technique_tactic}/{@code
 * attack_technique_mitigation} join tables (populated directly here rather than via a full sync,
 * mirroring AttackService.saveMatrix's own resolution logic), {@code attackTechnique.tactics.*}/{@code
 * attackTechnique.mitigations.*} nesting, the reverse {@code mitigation.techniques.*}, and that {@code
 * detection.attackTechnique.*}/{@code finding.attackTechnique.*} resolve correctly now that this namespace switched
 * from KB_FEDERATED to a RelationAqlField.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AttackAqlRegistryIT {

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

    @Autowired private AttackTacticRepository tacticRepository;
    @Autowired private AttackTechniqueRepository techniqueRepository;
    @Autowired private AttackMitigationRepository mitigationRepository;
    @Autowired private AttackTechniqueTacticRepository techniqueTacticRepository;
    @Autowired private AttackTechniqueMitigationRepository techniqueMitigationRepository;
    @Autowired private DetectionRepository detectionRepository;
    @Autowired private DetectionStatusRepository detectionStatusRepository;
    @Autowired private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;
    @Autowired private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private AttackAqlRegistry attackRegistry;
    private PostgresSpecificationCompiler<AttackTechnique> attackCompiler;
    private DetectionAqlRegistry detectionRegistry;
    private PostgresSpecificationCompiler<Detection> detectionCompiler;

    private long projectId;
    private AttackTechnique technique;
    private AttackMitigation mitigation;

    @BeforeEach
    void seed() {
        attackRegistry = new AttackAqlRegistry();
        var tacticRegistry = new AttackTacticAqlRegistry();
        var mitigationRegistry = new AttackMitigationAqlRegistry();
        var cveRegistry = new CveAqlRegistry();
        var cveKevDetailRegistry = new CveKevDetailAqlRegistry();
        var owaspRegistry = new OwaspAqlRegistry();
        var cweRegistry = new CweAqlRegistry();
        var capecRegistry = new CapecAqlRegistry();
        var assetRegistry = new AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new FindingAqlRegistry(fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        detectionRegistry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        new AqlRegistryLookup(List.of(attackRegistry, tacticRegistry, mitigationRegistry, cveRegistry,
            cveKevDetailRegistry, owaspRegistry, cweRegistry, capecRegistry, assetRegistry, findingRegistry, detectionRegistry,
            new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        attackCompiler = new PostgresSpecificationCompiler<>(attackRegistry);
        detectionCompiler = new PostgresSpecificationCompiler<>(detectionRegistry);

        techniqueMitigationRepository.deleteAll();
        techniqueTacticRepository.deleteAll();
        techniqueRepository.deleteAll();
        tacticRepository.deleteAll();
        mitigationRepository.deleteAll();
        detectionRepository.deleteAll();

        AttackTactic tactic = new AttackTactic();
        tactic.setAttackId("TA0001");
        tactic.setName("Initial Access");
        tactic.setShortName("initial-access");
        tactic.setMatrix("enterprise-attack");
        tactic = tacticRepository.save(tactic);

        technique = new AttackTechnique();
        technique.setStixId("attack-pattern--bbbb");
        technique.setAttackId("T1566");
        technique.setName("Phishing");
        technique.setMatrix("enterprise-attack");
        technique.setTactics(List.of("initial-access"));
        technique.setPlatforms(List.of("Windows", "Linux"));
        technique = techniqueRepository.save(technique);

        mitigation = new AttackMitigation();
        mitigation.setStixId("course-of-action--cccc");
        mitigation.setAttackId("M1017");
        mitigation.setName("User Training");
        mitigation.setMatrix("enterprise-attack");
        mitigation = mitigationRepository.save(mitigation);

        techniqueTacticRepository.save(new AttackTechniqueTactic(technique.getId(), tactic.getId()));
        techniqueMitigationRepository.save(new AttackTechniqueMitigation(technique.getId(), mitigation.getId()));

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('ATTCK Test Org', 'attck-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'ATTCK Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();

        Long attackCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'ATT&CK'")
            .getSingleResult()).longValue();

        Detection detection = new Detection();
        detection.setProjectId(projectId);
        detection.setTitle("Detection linked to T1566");
        detection.setSeverity("critical");
        detection.setPriority(PriorityThresholds.fromSeverityName("critical"));
        detection.setStatus("new");
        detection.setStatusId(detectionStatusRepository.findByName("new").orElseThrow().getId());
        detection.setCreatedAt(OffsetDateTime.now());
        detection.setUpdatedAt(OffsetDateTime.now());
        detectionRepository.save(detection);

        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, 'T1566') RETURNING id")
            .setParameter("catalogId", attackCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", detection.getId()).executeUpdate();
    }

    private List<AttackTechnique> runAttack(String aql) {
        Specification<AttackTechnique> spec = attackCompiler.compile(AqlParser.parse(aql));
        return techniqueRepository.findAll(spec);
    }

    private List<Detection> runDetection(String aql) {
        Specification<Detection> spec = detectionCompiler.compile(AqlParser.parse(aql));
        return detectionRepository.findAll(spec);
    }

    @Test
    void directAqlQueryOnAttackId() {
        assertEquals(1, runAttack("id == \"T1566\"").size());
    }

    @Test
    void techniqueTacticBridgeRowExists() {
        assertEquals(1, techniqueTacticRepository.count());
    }

    /** Regression test for a real bug this session's live sync verification caught: the two
     *  bridge repositories' custom {@code @Modifying} deleteByMatrix queries threw
     *  TransactionRequiredException at runtime (compiled fine, only failed when actually called
     *  from AttackService.saveMatrix) because Spring Data JPA does NOT automatically wrap custom
     *  @Query methods transactionally the way it wraps its own generated save()/saveAll() —
     *  needed an explicit @Transactional on the repository method. This is exactly the class of
     *  bug the IT test suite hadn't caught before, since the read-only query tests above never
     *  exercised the delete-then-reinsert sync path. */
    @Test
    void deleteByMatrixDoesNotThrowTransactionRequiredException() {
        techniqueTacticRepository.deleteByMatrix("enterprise-attack");
        techniqueMitigationRepository.deleteByMatrix("enterprise-attack");
        assertEquals(0, techniqueTacticRepository.count());
        assertEquals(0, techniqueMitigationRepository.count());
    }

    @Test
    void techniqueMitigationBridgeRowExists() {
        assertEquals(1, techniqueMitigationRepository.count());
    }

    @Test
    void attackTacticsRelationLeavesAreRegistered() {
        assertTrue(attackRegistry.field("tactics.id").isPresent());
        assertTrue(attackRegistry.field("tactics.shortName").isPresent());
    }

    @Test
    void attackMitigationsRelationLeavesAreRegistered() {
        assertTrue(attackRegistry.field("mitigations.id").isPresent());
        assertTrue(attackRegistry.field("mitigations.name").isPresent());
    }

    /** Regression: platforms is stored as-cased ("Windows", not "windows" — ares-ui renders it
     *  directly as a badge) yet HAS must still match case-insensitively, same as every other AQL
     *  string comparison — proving ares.array_contains_ci (V157) works against a display-cased
     *  array, not just the lowercase-normalized arrays every other HAS field in this codebase uses. */
    @Test
    void platformsHasMatchesCaseInsensitivelyAgainstDisplayCasedStorage() {
        assertEquals(1, runAttack("platforms HAS \"windows\"").size());
        assertEquals(1, runAttack("platforms HAS \"Windows\"").size());
        assertEquals(1, runAttack("platforms HAS \"WINDOWS\"").size());
        assertEquals(0, runAttack("platforms HAS \"macos\"").size());
    }

    @Test
    void queryingByTacticShortNameThroughTheJoinTableWorks() {
        assertEquals(1, runAttack("tactics.shortName == \"initial-access\"").size());
        assertEquals(0, runAttack("tactics.shortName == \"execution\"").size());
    }

    /** The core new-feature regression: a technique's mitigation must be reachable purely via
     *  the STIX "mitigates" relationship AttackStixParser now parses (Phase 5's confirmed-in-scope
     *  new capability) — not via any pre-existing field. */
    @Test
    void queryingByMitigationAttackIdThroughTheNewJoinTableWorks() {
        assertEquals(1, runAttack("mitigations.id == \"M1017\"").size());
        assertEquals(0, runAttack("mitigations.id == \"M9999\"").size());
    }

    @Test
    void transitiveDetectionAttackQueryWorks() {
        assertEquals(1, runDetection("attackTechnique.id == \"T1566\"").size());
        assertEquals(0, runDetection("attackTechnique.id == \"T9999\"").size());
    }

    /** Proves the transitive chain goes a full THREE hops: detection -> attackTechnique -> mitigations —
     *  exactly the kind of relation-of-a-relation chain the multi-round AqlRegistryLookup
     *  expansion fix (this session, Phase 4) exists to make work. */
    @Test
    void transitiveDetectionAttackMitigationsQueryWorks() {
        assertTrue(detectionRegistry.field("attackTechnique.mitigations.id").isPresent());
        assertEquals(1, runDetection("attackTechnique.mitigations.id == \"M1017\"").size());
    }
}
