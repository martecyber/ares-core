package com.martecyber.ares.kb.kev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Real-Postgres coverage for the {@code ares.cve_kev_detail} upsert-by-(cveId, source) shape both
 * {@link CisaKevSyncRunner} and {@link VulnCheckKevSyncRunner} rely on, and specifically for
 * {@link CveKevDetailRepository#deleteBySource} — the one operation where a mistake (a bare
 * {@code deleteAll()}, matching the old per-source-Mongo-collection code almost verbatim) would
 * silently wipe the OTHER source's rows too now that both share one table (AQL-wide initiative,
 * Phase 4).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CveKevDetailRepositoryIT {

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
    private CveKevDetailRepository repo;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        repo.save(row("CVE-2099-0001", "cisa", "Cisa One"));
        repo.save(row("CVE-2099-0002", "cisa", "Cisa Two"));
        repo.save(row("CVE-2099-0001", "vulncheck", "VulnCheck One"));
    }

    private CveKevDetail row(String cveId, String source, String name) {
        CveKevDetail e = new CveKevDetail();
        e.setCveId(cveId);
        e.setSource(source);
        e.setVulnerabilityName(name);
        return e;
    }

    @Test
    void findByCveIdAndSourceIsSourceScoped() {
        assertTrue(repo.findByCveIdAndSource("CVE-2099-0001", "cisa").isPresent());
        assertTrue(repo.findByCveIdAndSource("CVE-2099-0001", "vulncheck").isPresent());
        assertTrue(repo.findByCveIdAndSource("CVE-2099-0002", "vulncheck").isEmpty());
    }

    @Test
    void countBySourceIsIsolatedPerSource() {
        assertEquals(2, repo.countBySource("cisa"));
        assertEquals(1, repo.countBySource("vulncheck"));
    }

    @Test
    void findBySourcePagedReturnsOnlyThatSource() {
        var page = repo.findBySource("cisa", PageRequest.of(0, 50));
        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(e -> "cisa".equals(e.getSource())));
    }

    /** The critical regression test: deleting one source's rows must NEVER touch the other's —
     *  the exact bug a naive port of the old Mongo-era {@code repo.deleteAll()} would reintroduce. */
    @Test
    void deleteBySourceDoesNotTouchTheOtherSource() {
        repo.deleteBySource("cisa");
        assertEquals(0, repo.countBySource("cisa"));
        assertEquals(1, repo.countBySource("vulncheck"));
        assertTrue(repo.findByCveIdAndSource("CVE-2099-0001", "vulncheck").isPresent());
    }

    @Test
    void findByCveIdInAndSourceFiltersBothDimensions() {
        var results = repo.findByCveIdInAndSource(java.util.List.of("CVE-2099-0001", "CVE-2099-0002"), "cisa");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> "cisa".equals(e.getSource())));
    }

    @Test
    void searchMatchesWithinTheGivenSourceOnly() {
        var results = repo.search("cisa", "One", PageRequest.of(0, 50));
        assertEquals(1, results.getTotalElements());
        assertEquals("Cisa One", results.getContent().get(0).getVulnerabilityName());
    }
}
