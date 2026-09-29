package com.martecyber.ares.aql;

import com.martecyber.ares.findings.templates.FindingTemplateAqlRegistry;
import com.martecyber.ares.findings.templates.FindingTemplateRepository;
import com.martecyber.ares.kb.attack.AttackAqlRegistry;
import com.martecyber.ares.kb.attack.AttackTechnique;
import com.martecyber.ares.kb.attack.AttackTechniqueRepository;
import com.martecyber.ares.kb.capec.CapecAqlRegistry;
import com.martecyber.ares.kb.capec.CapecRepository;
import com.martecyber.ares.kb.cve.CveAqlRegistry;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.kb.cwe.CweAqlRegistry;
import com.martecyber.ares.kb.cwe.CweRepository;
import com.martecyber.ares.kb.exploits.ExploitAqlRegistry;
import com.martecyber.ares.kb.exploits.ExploitRepository;
import com.martecyber.ares.kb.owasp.OwaspAqlRegistry;
import com.martecyber.ares.kb.owasp.OwaspRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Real-Postgres coverage for {@link AqlQueryableEntityRegistry} — the generic executor backing
 * ASSIGN_VARIABLE sources for every AQL-registered entity except asset/finding/detection (see that
 * class's own doc comment for why those three are excluded). Exercises both whole-entity mode
 * ({@code queryEntities}) and field-projection mode ({@code queryProjected}), including an
 * {@code ARRAY_COLUMN} field to confirm per-row flattening.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AqlQueryableEntityRegistryIT {

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
    @Autowired private CapecRepository capecRepository;
    @Autowired private ExploitRepository exploitRepository;
    @Autowired private OwaspRepository owaspRepository;
    @Autowired private AttackTechniqueRepository attackTechniqueRepository;
    @Autowired private FindingTemplateRepository findingTemplateRepository;

    @PersistenceContext
    private EntityManager em;

    private AqlQueryableEntityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AqlQueryableEntityRegistry(
            new CveAqlRegistry(), cveRepository,
            new CweAqlRegistry(), cweRepository,
            new CapecAqlRegistry(), capecRepository,
            new ExploitAqlRegistry(), exploitRepository,
            new OwaspAqlRegistry(), owaspRepository,
            new AttackAqlRegistry(), attackTechniqueRepository,
            new FindingTemplateAqlRegistry(), findingTemplateRepository);
        ReflectionTestUtils.setField(registry, "em", em);

        CveEntry cve = new CveEntry();
        cve.setCveId("CVE-2099-" + System.nanoTime() % 100000);
        cve.setSeverity("critical");
        cveRepository.save(cve);

        AttackTechnique t1 = new AttackTechnique();
        t1.setAttackId("T-IT-" + System.nanoTime() % 100000);
        t1.setName("Fixture technique 1");
        t1.setMatrix("enterprise");
        t1.setPlatforms(List.of("Windows", "Linux"));
        attackTechniqueRepository.save(t1);

        AttackTechnique t2 = new AttackTechnique();
        t2.setAttackId("T-IT-" + (System.nanoTime() % 100000 + 1));
        t2.setName("Fixture technique 2");
        t2.setMatrix("enterprise");
        t2.setPlatforms(List.of("macOS"));
        attackTechniqueRepository.save(t2);

        em.flush();
    }

    @Test
    void queryEntitiesReturnsWholeMatchingRowsForAKbEntity() {
        List<AttackTechnique> result = registry.queryEntities("attackTechnique", "matrix == enterprise", 10);
        assertTrue(result.size() >= 2);
        assertTrue(result.stream().anyMatch(t -> "Fixture technique 1".equals(t.getName())));
    }

    @Test
    void queryProjectedFlattensAnArrayColumnFieldAcrossMatchingRows() {
        List<Object> platforms = registry.queryProjected("attackTechnique", "matrix == enterprise", "platforms", 50);
        Set<Object> values = Set.copyOf(platforms);
        assertTrue(values.contains("Windows"));
        assertTrue(values.contains("Linux"));
        assertTrue(values.contains("macOS"));
    }

    @Test
    void queryProjectedReturnsAPlainColumnValuePerMatchingRow() {
        List<Object> names = registry.queryProjected("attackTechnique", "matrix == enterprise", "name", 50);
        assertTrue(names.contains("Fixture technique 1"));
        assertTrue(names.contains("Fixture technique 2"));
    }

    @Test
    void unsupportedEntityTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> registry.queryEntities("asset", "id == 1", 10));
    }

    @Test
    void projectingANonColumnFieldIsRejected() {
        // "cwe" on CveAqlRegistry is a RelationAqlField (array-membership relation onto CweEntry),
        // not a PostgresColumnField — not projectable.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.queryProjected("cve", "severity == critical", "cwe", 10));
        assertTrue(ex.getMessage().contains("can't be projected"));
    }
}
