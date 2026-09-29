package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.common.PriorityThresholds;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionAqlRegistry;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionStatusRepository;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises PostgresSpecificationCompiler against a real, fully-migrated Postgres instance
 * (all Flyway migrations run, same as production) rather than a mocked CriteriaBuilder, so it
 * also doubles as a regression check that the migration history and Detection's entity mapping
 * stay consistent with each other.
 *
 * Connects to a Postgres instance whose JDBC port is supplied via the {@code AQL_IT_PG_PORT}
 * system property (defaults to 15432) — the environment this test currently runs in has a
 * testcontainers/dockerd API-version mismatch (dockerd requires API >= 1.40, the bundled
 * docker-java client only speaks 1.32), so the container is started out-of-band by the test
 * runner instead of via testcontainers' Docker client. Swap back to @Testcontainers/@Container
 * once that mismatch is resolved (e.g. testcontainers upgrade) — the rest of this test is
 * container-mechanism-agnostic.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostgresSpecificationCompilerDetectionIT {

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

    @Autowired
    private DetectionRepository detectionRepository;

    @Autowired
    private DetectionStatusRepository detectionStatusRepository;

    @Autowired
    private com.martecyber.ares.references.ReferenceCatalogRepository referenceCatalogRepository;

    @Autowired
    private CveRepository cveRepository;

    @Autowired
    private com.martecyber.ares.aql.registry.FieldDefinitionRepository fieldDefinitionRepository;

    @Autowired
    private com.martecyber.ares.findings.FindingStatusRepository findingStatusRepository;

    @Autowired
    private com.martecyber.ares.detections.DetectionTagRepository detectionTagRepository;

    @Autowired
    private com.martecyber.ares.tags.TagRepository tagRepository;

    @PersistenceContext
    private EntityManager em;

    // Can't be a field initializer — @Autowired fields aren't populated yet when instance field
    // initializers run (JUnit constructs the test instance first; SpringExtension injects
    // @Autowired fields afterwards), so DetectionAqlRegistry(referenceCatalogRepository) has to
    // wait until @BeforeEach.
    private DetectionAqlRegistry registry;
    private PostgresSpecificationCompiler<Detection> compiler;

    private long projectId;
    private long organizationId;

    @BeforeEach
    void seed() {
        registry = new DetectionAqlRegistry(referenceCatalogRepository, detectionStatusRepository);
        // Must go through AqlRegistryLookup, not use DetectionAqlRegistry standalone — the
        // "cve.<leaf>"/"asset.<leaf>"/"findings.<leaf>" flat entries only get built by its
        // post-construction expandRelations pass (Phase 2/3 of the AQL-wide initiative).
        // DetectionAqlRegistry itself registers RelationAqlFields against all three (cve, asset,
        // findings) at construction time, so all three sibling registries must be present in the
        // same lookup or expandRelations throws "Unknown AQL entity" for whichever is missing.
        // assetRegistry also registers a "scope" relation against projectAssetAccess, so that
        // registry has to be present here too even though this test never queries scope.* itself.
        var assetRegistry = new com.martecyber.ares.assets.AssetAqlRegistry(fieldDefinitionRepository);
        var findingRegistry = new com.martecyber.ares.findings.FindingAqlRegistry(
            fieldDefinitionRepository, findingStatusRepository, referenceCatalogRepository);
        new AqlRegistryLookup(List.of(registry, new CveAqlRegistry(),
            new com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry(), assetRegistry, findingRegistry,
            new com.martecyber.ares.kb.owasp.OwaspAqlRegistry(), new com.martecyber.ares.kb.cwe.CweAqlRegistry(),
            new com.martecyber.ares.kb.capec.CapecAqlRegistry(), new com.martecyber.ares.kb.attack.AttackAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(), new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry(),
            new com.martecyber.ares.tags.TagAqlRegistry(), new com.martecyber.ares.projects.ProjectAssetAccessAqlRegistry()));
        compiler = new PostgresSpecificationCompiler<>(registry);
        detectionTagRepository.deleteAll();
        tagRepository.deleteAll();
        cveRepository.deleteAll();
        detectionRepository.deleteAll();
        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('AQL Test Org', 'aql-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'AQL Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        this.projectId = projId.longValue();
        this.organizationId = orgId.longValue();

        detectionRepository.save(detection("Critical XSS on login", "critical", "new", 42));
        detectionRepository.save(detection("Minor typo in footer", "info", "fixed", 1));
        detectionRepository.save(detection("SQL Injection risk in search", "high", "new", 3));
    }

    private Detection detection(String title, String severity, String status, int occurrenceCount) {
        Detection d = new Detection();
        d.setProjectId(projectId);
        d.setTitle(title);
        d.setSeverity(severity);
        d.setPriority(PriorityThresholds.fromSeverityName(severity));
        d.setStatus(status);
        d.setStatusId(detectionStatusRepository.findByName(status).orElseThrow().getId());
        d.setOccurrenceCount(occurrenceCount);
        d.setCreatedAt(OffsetDateTime.now());
        d.setUpdatedAt(OffsetDateTime.now());
        return d;
    }

    private List<Detection> run(String aql) {
        Specification<Detection> spec = compiler.compile(AqlParser.parse(aql));
        List<Detection> results = detectionRepository.findAll(spec);
        results.sort(Comparator.comparing(Detection::getTitle));
        return results;
    }

    @Test
    void filtersBySingleEquality() {
        List<Detection> results = run("priority == P0");
        assertEquals(1, results.size());
        assertEquals("Critical XSS on login", results.get(0).getTitle());
    }

    @Test
    void filtersById() {
        Long id = detectionRepository.findAll().stream()
            .filter(d -> "Minor typo in footer".equals(d.getTitle())).findFirst().orElseThrow().getId();
        List<Detection> results = run("id == " + id);
        assertEquals(1, results.size());
        assertEquals("Minor typo in footer", results.get(0).getTitle());
    }

    @Test
    void equalityIsCaseInsensitive() {
        assertEquals(1, run("priority == p0").size());
    }

    @Test
    void combinesGroupedOrWithAnd() {
        assertEquals(2, run("(priority == P0 OR priority == P1) AND status == new").size());
    }

    @Test
    void severityFieldNoLongerExists() {
        // Locked-in decision: severity is fully derived from priority now — there is no
        // separate queryable "severity" AQL field, only "priority" (P0-P4).
        org.junit.jupiter.api.Assertions.assertThrows(
            com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
            () -> run("severity == critical"));
    }

    @Test
    void priorityRejectsRawIntegersAndOldSeverityWords() {
        org.junit.jupiter.api.Assertions.assertThrows(
            AqlCompileException.class, () -> run("priority == 0"));
        org.junit.jupiter.api.Assertions.assertThrows(
            AqlCompileException.class, () -> run("priority == critical"));
    }

    @Test
    void notNegatesClause() {
        assertEquals(2, run("NOT status == fixed").size());
    }

    @Test
    void minusIsShorthandForNot() {
        assertEquals(2, run("-status == fixed").size());
    }

    @Test
    void rangeOnPriorityField() {
        // priority (V144): P0=critical, P1=high, P2=medium, P3=low, P4=info -> P0 & P1 qualify for <=P1
        assertEquals(2, run("priority <= P1").size());
    }

    @Test
    void bareTermSearchesTitleAndDescription() {
        List<Detection> results = run("injection");
        assertEquals(1, results.size());
        assertEquals("SQL Injection risk in search", results.get(0).getTitle());
    }

    @Test
    void listValueCompilesToIn() {
        assertEquals(2, run("priority == [P0,P1]").size());
    }

    @Test
    void listValueWithNeqCompilesToNotIn() {
        assertEquals(1, run("priority != [P0,P1]").size());
    }

    /** The IN keyword is exactly the same compiled path as EQ+list (AqlOperator's own doc
     *  comment) — same result set, just spelled differently. */
    @Test
    void inKeywordCompilesIdenticallyToEqualsWithList() {
        assertEquals(run("priority == [P0,P1]"), run("priority IN [P0,P1]"));
    }

    /** Regression test: the list-value path (EQ/IN with [a,b,c]) must lowercase-and-cast the
     *  *column* expression exactly like plain EQ already does, not just the literal values on the
     *  Java side — otherwise a STRING field storing genuinely mixed/upper case data (real CVE
     *  "severity" values are e.g. "CRITICAL", not the lowercase this fixture's title happens to
     *  partially avoid) silently returns zero rows for every IN/list-EQ query. "title" here is
     *  seeded as "Critical XSS on login" (mixed case) specifically to catch this. */
    @Test
    void listValueOnAStringFieldIsCaseInsensitiveAgainstMixedCaseStoredData() {
        assertEquals(1, run("title IN [\"critical xss on login\"]").size());
        assertEquals(1, run("title == [\"critical xss on login\"]").size());
        assertEquals(2, run("title != [\"critical xss on login\"]").size());
    }

    @Test
    void inKeywordIsCaseInsensitive() {
        assertEquals(2, run("priority in [P0,P1]").size());
    }

    @Test
    void notWrappingInNegatesCorrectly() {
        assertEquals(1, run("NOT (priority IN [P0,P1])").size());
    }

    @Test
    void unknownFieldIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
            com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
            () -> run("notAField == foo"));
    }

    /** RELATION path (AQL-wide initiative, Phase 3): cve.* now compiles to an EXISTS join
     *  straight against the real ares.cve table (via the same ReferenceEntry bridge the old
     *  KB_MATERIALIZED join used) — no Mongo/kb_materialized_ref involved at all anymore. */
    @Test
    void cveKevListedCompilesToARelationJoinAgainstTheRealCveTable() {
        Long xssDetectionId = detectionRepository.findAll().stream()
            .filter(d -> "Critical XSS on login".equals(d.getTitle()))
            .findFirst().orElseThrow().getId();

        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2099-0001");
        cve.setKevListed(true);
        cve.setAnyKevListed(true);
        cve.setCvssScore(9.9);
        cve.setSeverity("critical");
        cve.setExploitCount(5);
        cveRepository.save(cve);

        Long cveCatalogId = ((Number) em.createNativeQuery(
                "SELECT id FROM ares.reference_catalog WHERE code = 'CVE'")
            .getSingleResult()).longValue();
        Long refId = ((Number) em.createNativeQuery(
                "INSERT INTO ares.reference_entry(catalog_id, title) VALUES (:catalogId, 'CVE-2099-0001') RETURNING id")
            .setParameter("catalogId", cveCatalogId)
            .getSingleResult()).longValue();
        em.createNativeQuery(
                "INSERT INTO ares.reference_entry_detection(reference_entry_id, detection_id) VALUES (:refId, :detId)")
            .setParameter("refId", refId).setParameter("detId", xssDetectionId).executeUpdate();

        List<Detection> kevMatches = run("cve.kevListed == true");
        assertEquals(1, kevMatches.size());
        assertEquals("Critical XSS on login", kevMatches.get(0).getTitle());

        assertEquals(0, run("cve.kevListed == false").size());
        assertEquals(1, run("cve.cvssScore >= 9").size());
        assertEquals(1, run("priority == P0 AND cve.kevListed == true").size());
        assertEquals(1, run("cve.id == \"CVE-2099-0001\"").size());
    }

    @Test
    void cveIdIsRegisteredAsARelationLeaf() {
        var field = registry.field("cve.id").orElseThrow();
        assertEquals(com.martecyber.ares.aql.registry.AqlFieldKind.RELATION, field.kind());
        assertEquals(com.martecyber.ares.aql.registry.AqlFieldType.STRING, field.type());
    }

    @Test
    void cveLastModifiedAtIsRegistered() {
        assertTrue(registry.field("cve.lastModifiedAt").isPresent());
    }

    /** Every cwe./capec./owasp./attack. namespace exposes its own identifier field, matching the
     *  standalone CweAqlRegistry/CapecAqlRegistry/OwaspAqlRegistry/AttackAqlRegistry field names
     *  exactly — the same class of gap cve.id was missing before this. */
    @Test
    void everyKbNamespaceExposesItsOwnIdentifierField() {
        assertTrue(registry.field("cwe.id").isPresent());
        assertTrue(registry.field("capec.id").isPresent());
        assertTrue(registry.field("owasp.id").isPresent());
        assertTrue(registry.field("attackTechnique.id").isPresent());
    }

    /** tags.* — via the detection_tag join table (org-scoped tag, this session's new "tags on
     *  detection/finding/exploit/finding_template" feature). */
    @Test
    void tagsRelationMatchesAssignedOrgTag() {
        var tag = new com.martecyber.ares.tags.Tag();
        tag.setOrganizationId(organizationId);
        tag.setName("Needs retest");
        tag.setColor("#F97316");
        tag.setCreatedAt(OffsetDateTime.now());
        tag.setUpdatedAt(OffsetDateTime.now());
        tag = tagRepository.save(tag);

        Long xssDetectionId = detectionRepository.findAll().stream()
            .filter(d -> "Critical XSS on login".equals(d.getTitle()))
            .findFirst().orElseThrow().getId();
        detectionTagRepository.assign(xssDetectionId, tag.getId());

        assertEquals(1, run("tags.name == \"Needs retest\"").size());
        assertEquals(0, run("tags.name == \"Nonexistent\"").size());
    }

    /** status.<name> (dashboards remodel, Phase 13) — a virtual DATE field, not a relation. */
    @Test
    void statusTransitionFieldIsRegisteredAsADateField() {
        var field = registry.field("status.affected").orElseThrow();
        assertEquals(com.martecyber.ares.aql.registry.AqlFieldKind.STATUS_TRANSITION, field.kind());
        assertEquals(com.martecyber.ares.aql.registry.AqlFieldType.DATE, field.type());
    }

    /** status.<name> filters on WHEN a detection FIRST transitioned into that status, not whether
     *  it's currently in it — the XSS detection here escalates twice (affected, reopened, then
     *  re-affected); the query must key off the earlier transition, proving the compiled subquery
     *  is really {@code MIN(changed_at)} and not the latest row. A detection that never
     *  transitioned into a status must not match ANY comparison against it — ordinary SQL NULL
     *  semantics on the correlated subquery's result, exercised here via "fixed" (a status
     *  neither fixture ever reaches). */
    @Test
    void statusTransitionFieldFiltersByFirstTransitionIntoThatStatus() {
        Long xssDetectionId = detectionRepository.findAll().stream()
            .filter(d -> "Critical XSS on login".equals(d.getTitle()))
            .findFirst().orElseThrow().getId();
        Long sqliDetectionId = detectionRepository.findAll().stream()
            .filter(d -> "SQL Injection risk in search".equals(d.getTitle()))
            .findFirst().orElseThrow().getId();

        insertDetectionStatusHistory(xssDetectionId, "new", "affected", OffsetDateTime.parse("2026-01-10T00:00:00Z"));
        insertDetectionStatusHistory(xssDetectionId, "reopened", "affected", OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        insertDetectionStatusHistory(sqliDetectionId, "new", "affected", OffsetDateTime.parse("2026-06-01T00:00:00Z"));

        assertEquals(0, run("status.affected < 2026-01-01").size());
        // Between the XSS detection's first and second escalation: only it qualifies.
        List<Detection> beforeFeb = run("status.affected < 2026-02-01");
        assertEquals(1, beforeFeb.size());
        assertEquals("Critical XSS on login", beforeFeb.get(0).getTitle());
        // After both detections' first escalation.
        assertEquals(2, run("status.affected < 2026-07-01").size());
        assertEquals(1, run("status.affected >= 2026-05-01").size());

        assertEquals(0, run("status.fixed < now").size());
        assertEquals(0, run("status.fixed >= now-100d").size());
    }

    private void insertDetectionStatusHistory(Long detectionId, String fromStatus, String toStatus, OffsetDateTime changedAt) {
        em.createNativeQuery(
                "INSERT INTO ares.detection_status_history(detection_id, event_type, from_status, to_status, changed_at) "
                    + "VALUES (:detId, 'status_changed', :from, :to, :changedAt)")
            .setParameter("detId", detectionId)
            .setParameter("from", fromStatus)
            .setParameter("to", toStatus)
            .setParameter("changedAt", changedAt)
            .executeUpdate();
    }
}
