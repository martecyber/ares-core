package com.martecyber.ares.aql.compile;

import com.martecyber.ares.findings.templates.FindingTemplate;
import com.martecyber.ares.findings.templates.FindingTemplateAqlRegistry;
import com.martecyber.ares.findings.templates.FindingTemplateRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises PostgresSpecificationCompiler against FindingTemplate's registry (AQL implementation
 * plan, Phase 4) — mirrors the sibling Detection/Asset ITs. FindingTemplate has no
 * organization_id (platform-wide catalog), so there's no scope predicate to also assert here,
 * unlike Detection/Asset/Finding.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostgresSpecificationCompilerFindingTemplateIT {

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
    private FindingTemplateRepository repo;

    private final FindingTemplateAqlRegistry registry = new FindingTemplateAqlRegistry();
    private PostgresSpecificationCompiler<FindingTemplate> compiler;

    @BeforeEach
    void seed() {
        compiler = new PostgresSpecificationCompiler<>(registry);
        repo.deleteAll();
        repo.save(template("Reflected XSS", "high"));
        repo.save(template("Outdated jQuery", "low"));
        repo.save(template("SQL Injection in search", "critical"));
    }

    private FindingTemplate template(String title, String severity) {
        FindingTemplate t = new FindingTemplate();
        t.setTitle(title);
        t.setSeverity(severity);
        t.setCreatedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        return t;
    }

    private List<FindingTemplate> run(String aql) {
        Specification<FindingTemplate> spec = compiler.compile(com.martecyber.ares.aql.parser.AqlParser.parse(aql));
        List<FindingTemplate> results = repo.findAll(spec);
        results.sort(Comparator.comparing(FindingTemplate::getTitle));
        return results;
    }

    @Test
    void filtersByPriorityEquality() {
        // priority is a read-only formula bridge off severity (FindingTemplate has no real
        // priority column) — critical -> P0.
        List<FindingTemplate> results = run("priority == P0");
        assertEquals(1, results.size());
        assertEquals("SQL Injection in search", results.get(0).getTitle());
    }

    @Test
    void bareTermSearchesTitle() {
        List<FindingTemplate> results = run("jquery");
        assertEquals(1, results.size());
        assertEquals("Outdated jQuery", results.get(0).getTitle());
    }

    @Test
    void combinesContainsWithNot() {
        assertEquals(2, run("NOT priority == P0").size());
    }

    @Test
    void severityFieldNoLongerExists() {
        org.junit.jupiter.api.Assertions.assertThrows(
            com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
            () -> run("severity == critical"));
    }

    @Test
    void unknownFieldIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
            com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
            () -> run("notAField == foo"));
    }
}
