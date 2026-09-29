package com.martecyber.ares.kb.cve;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.compile.AqlCompileException;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.kb.cwe.CweAqlRegistry;
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.cwe.CweRepository;
import com.martecyber.ares.kb.kev.CveKevDetailAqlRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises PostgresSpecificationCompiler's RELATION path for {@code cwes} (a {@code list[cwe]}
 * array-membership relation, not a bare ARRAY_COLUMN/HAS field — see {@link
 * com.martecyber.ares.aql.registry.RelationAqlField#listOf}) against real, fully-migrated
 * Postgres — the one shape neither PostgresSpecificationCompilerDetectionIT nor any other existing
 * IT covered. This used to guard a real bug in the plain HAS path this field originally used
 * ({@code cb.function("array_position", ...)} colliding with Hibernate 6.4+'s own registered
 * function of the same name — see PostgresSpecificationCompiler.arrayContainsPredicate's own doc
 * comment); {@code array_contains_ci} (V157) replaced that mechanism for both HAS and this
 * relation's correlation, so this file keeps the same "matches only the right row, not every
 * non-empty array" regression shape, just expressed as {@code cwe.id == ...} now.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CveAqlRegistryIT {

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
    private CveRepository cveRepository;
    @Autowired
    private CweRepository cweRepository;

    private CveAqlRegistry registry;
    private PostgresSpecificationCompiler<CveEntry> compiler;

    @BeforeEach
    void seed() {
        registry = new CveAqlRegistry();
        // Full transitive closure: cwes (this registry's own list[cwe]) -> cwe -> capec -> attack
        // -> {attackTactic, attackMitigation}; kev (pre-existing relation) -> cveKevDetail -> cwe.
        new AqlRegistryLookup(List.of(registry, new CweAqlRegistry(), new CveKevDetailAqlRegistry(),
            new com.martecyber.ares.kb.capec.CapecAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackAqlRegistry(), new com.martecyber.ares.kb.attack.AttackTacticAqlRegistry(),
            new com.martecyber.ares.kb.attack.AttackMitigationAqlRegistry()));
        compiler = new PostgresSpecificationCompiler<>(registry);
        cveRepository.deleteAll();
        cweRepository.deleteAll();

        // cwe is list[cwe] now (an array-membership RelationAqlField, not a bare HAS array) — the
        // nested "cwe.id == ..." queries below need real CweEntry rows to correlate against.
        for (String id : List.of("cwe-79", "cwe-89", "cwe-20", "cwe-200")) {
            CweEntry e = new CweEntry();
            e.setCweId(id.replace("cwe-", ""));
            // cwe.id resolves against CweEntry.code (the prefixed form), not the bare cweId.
            e.setCode(id);
            cweRepository.save(e);
        }

        cveRepository.save(cve("CVE-2099-0001", "CRITICAL", "cwe-79", "cwe-89"));
        // Deliberately non-matching but non-empty/non-null arrays — this is exactly the shape that
        // the old array_position/coalesce(...,0) bug matched incorrectly.
        cveRepository.save(cve("CVE-2099-0002", "HIGH", "cwe-20", "cwe-200"));
        cveRepository.save(cve("CVE-2099-0003", "MEDIUM"));
    }

    private CveEntry cve(String cveId, String severity, String... cwes) {
        CveEntry e = new CveEntry();
        e.setCveId(cveId);
        e.setSeverity(severity);
        if (cwes.length > 0) e.setCwes(List.of(cwes));
        return e;
    }

    private List<CveEntry> run(String aql) {
        Specification<CveEntry> spec = compiler.compile(AqlParser.parse(aql));
        return cveRepository.findAll(spec);
    }

    @Test
    void listRelationMatchesOnlyRowsActuallyContainingTheValue() {
        assertEquals(1, run("cwe.id == \"cwe-79\"").size());
        assertEquals("CVE-2099-0001", run("cwe.id == \"cwe-79\"").get(0).getCveId());
    }

    @Test
    void listRelationDoesNotMatchRowsWithADifferentNonEmptyArray() {
        // Regression: the old array_position-based HAS this replaced used to return all 3 rows
        // (any non-null array "matched") — array_contains_ci must not repeat that bug.
        assertEquals(1, run("cwe.id == \"cwe-20\"").size());
    }

    @Test
    void listRelationReturnsNothingForAValueNoRowHas() {
        assertEquals(0, run("cwe.id == \"zzz-nonexistent\"").size());
    }

    @Test
    void listRelationIsCaseInsensitive() {
        assertEquals(1, run("cwe.id == \"CWE-79\"").size());
    }

    @Test
    void listRelationCombinesWithAnotherCondition() {
        assertEquals(1, run("cwe.id == \"cwe-79\" AND severity == critical").size());
        assertEquals(0, run("cwe.id == \"cwe-79\" AND severity == high").size());
    }

    /** The bare "cwe" relation itself is never directly comparable — same rule every other
     *  RelationAqlField follows — only its flattened "cwe.<leaf>" entries are. */
    @Test
    void bareCwesFieldIsNotDirectlyComparable() {
        assertThrows(AqlCompileException.class, () -> run("cwe == \"cwe-79\""));
    }

    /** Regression test for the sibling bug found in the same verification pass: list-value EQ/IN
     *  must lowercase the *column* side too, not just the Java-side literal — real CVE severity is
     *  stored uppercase ("CRITICAL"), so comparing against a lowercased-only literal silently
     *  matched nothing. */
    @Test
    void inOperatorIsCaseInsensitiveAgainstUppercaseStoredData() {
        assertEquals(2, run("severity IN [critical,high]").size());
    }

    // ── affectedVendor / affectedProduct / affectedVersion (V185, JSONB_ARRAY_MATCH) ────────────

    private CveEntry.VersionRange affected(String version, String lessThan) {
        return new CveEntry.VersionRange(version, "affected", lessThan, null, "semver");
    }

    private CveEntry.VersionRange unaffectedFrom(String version) {
        return new CveEntry.VersionRange(version, "unaffected", null, null, "semver");
    }

    @Test
    void affectedVendorAndProductMatchCaseInsensitively() {
        CveEntry e = cve("CVE-2099-0010", "HIGH");
        e.setAffectedProducts(List.of(new CveEntry.AffectedProduct("Microsoft", "Windows", "unaffected",
            List.of(affected("1.0.0", "2.0.0")))));
        cveRepository.save(e);

        assertEquals(1, run("affectedVendor == microsoft").size());
        assertEquals(1, run("affectedVendor == \"MICROSOFT\"").size());
        assertEquals(1, run("affectedProduct == windows").size());
        assertEquals(0, run("affectedVendor == apple").size());
        assertEquals(0, run("affectedProduct == office").size());
    }

    @Test
    void affectedVersionMatchesInsideARangeAndNotOutsideIt() {
        CveEntry e = cve("CVE-2099-0011", "HIGH");
        e.setAffectedProducts(List.of(new CveEntry.AffectedProduct("acme", "widget", "unaffected",
            List.of(affected("1.0.0", "2.0.0")))));
        cveRepository.save(e);

        assertEquals(1, run("affectedVersion == \"1.5.0\"").size()); // inside [1.0.0, 2.0.0)
        assertEquals(1, run("affectedVersion == \"1.0.0\"").size()); // inclusive lower bound
        assertEquals(0, run("affectedVersion == \"2.0.0\"").size()); // exclusive upper bound
        assertEquals(0, run("affectedVersion == \"0.9.0\"").size()); // below the range
    }

    /** The bug plain lexicographic string comparison would produce: "1.9.0" < "1.10.0" numerically,
     *  but "1.10.0" < "1.9.0" as a string — the dotted-numeric comparator must get this right. */
    @Test
    void affectedVersionComparesSegmentsNumericallyNotLexicographically() {
        CveEntry e = cve("CVE-2099-0012", "HIGH");
        e.setAffectedProducts(List.of(new CveEntry.AffectedProduct("acme", "widget", "unaffected",
            List.of(affected("1.9.0", "1.20.0")))));
        cveRepository.save(e);

        assertEquals(1, run("affectedVersion == \"1.10.0\"").size());
        assertEquals(1, run("affectedVersion == \"1.19.9\"").size());
        assertEquals(0, run("affectedVersion == \"1.20.0\"").size());
    }

    /** A later "unaffected" (fixed) range overriding an earlier, broader "affected" one for the
     *  same product — the exact "affected but not in not-affected" semantics this feature exists
     *  for: 1.0.0 through 3.0.0 affected overall, except the 2.0.0-2.5.0 slice that got backported
     *  a fix. */
    @Test
    void aLaterUnaffectedRangeCarvesOutAFixedSliceOfAnEarlierAffectedRange() {
        CveEntry e = cve("CVE-2099-0013", "HIGH");
        CveEntry.VersionRange broadlyAffected = affected("1.0.0", "3.0.0");
        CveEntry.VersionRange backportedFix = new CveEntry.VersionRange("2.0.0", "unaffected", "2.5.0", null, "semver");
        e.setAffectedProducts(List.of(new CveEntry.AffectedProduct("acme", "widget", "unaffected",
            List.of(broadlyAffected, backportedFix))));
        cveRepository.save(e);

        assertEquals(1, run("affectedVersion == \"1.5.0\"").size());  // affected, before the carve-out
        assertEquals(0, run("affectedVersion == \"2.2.0\"").size());  // inside the backported-fix carve-out
        assertEquals(1, run("affectedVersion == \"2.8.0\"").size());  // affected again, after the carve-out
    }

    /** defaultStatus is the baseline when no explicit range covers the queried version at all. */
    @Test
    void defaultStatusAppliesWhenNoExplicitRangeCoversTheVersion() {
        CveEntry affectedByDefault = cve("CVE-2099-0014", "HIGH");
        affectedByDefault.setAffectedProducts(List.of(
            new CveEntry.AffectedProduct("acme", "widget", "affected", List.of(unaffectedFrom("9.0.0")))));
        cveRepository.save(affectedByDefault);

        CveEntry unaffectedByDefault = cve("CVE-2099-0015", "HIGH");
        unaffectedByDefault.setAffectedProducts(List.of(
            new CveEntry.AffectedProduct("acme", "gadget", "unaffected", List.of())));
        cveRepository.save(unaffectedByDefault);

        assertEquals(1, run("affectedVersion == \"1.0.0\" AND affectedProduct == widget").size());
        assertEquals(0, run("affectedVersion == \"1.0.0\" AND affectedProduct == gadget").size());
    }

    @Test
    void affectedVersionSupportsNeqNegation() {
        CveEntry e = cve("CVE-2099-0016", "HIGH");
        e.setAffectedProducts(List.of(new CveEntry.AffectedProduct("acme", "widget", "unaffected",
            List.of(affected("1.0.0", "2.0.0")))));
        cveRepository.save(e);

        // Scoped to this one CVE by id — every other fixture CVE has empty affectedProducts, so
        // "affectedVersion != X" is trivially true for them regardless of X (nothing is affected
        // at any version), which would swamp an unscoped assertion here with unrelated matches.
        assertEquals(0, run("id == \"CVE-2099-0016\" AND affectedVersion != \"1.5.0\"").size());
        assertEquals(1, run("id == \"CVE-2099-0016\" AND affectedVersion != \"9.9.9\"").size());
    }
}
