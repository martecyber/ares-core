package com.martecyber.ares.findings;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Verifies Finding.fields (jsonb, V141) actually round-trips through Hibernate/Postgres, and
 * that FindingFields.read/write agree with what's really stored — the part of the EAV-to-jsonb
 * cutover most likely to have a subtle mapping/annotation bug, independent of FindingService's
 * business logic (covered by manual code review; wiring FindingService itself here would also
 * require its full auth/org-scope collaborator graph, out of proportion for this check).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class FindingFieldsJsonbIT {

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
    private FindingRepository findingRepo;

    @PersistenceContext
    private EntityManager em;

    private long seedProject() {
        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('FF Test Org', 'ff-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        Number projectId = (Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'FF Test Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult();
        return projectId.longValue();
    }

    private long seedStatusId() {
        return ((Number) em.createNativeQuery("SELECT id FROM ares.finding_status ORDER BY id LIMIT 1")
            .getSingleResult()).longValue();
    }

    @Test
    void fieldsRoundTripThroughHibernateAndPostgres() {
        Finding f = new Finding();
        f.setProjectId(seedProject());
        f.setTitle("Round-trip test finding");
        f.setStatusId(seedStatusId());
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("impact", "Attacker can read arbitrary files");
        fields.put("remediation", "Upgrade to 2.1.4");
        FindingFields.write(f, fields);

        findingRepo.save(f);
        em.flush();
        em.clear();

        Finding reloaded = findingRepo.findById(f.getId()).orElseThrow();
        Map<String, String> readBack = FindingFields.read(reloaded);
        assertEquals(fields, readBack);
    }

    @Test
    void defaultFieldsIsEmptyMapNotNull() {
        Finding f = new Finding();
        f.setProjectId(seedProject());
        f.setTitle("No fields set");
        f.setStatusId(seedStatusId());
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());
        findingRepo.save(f);
        em.flush();
        em.clear();

        Finding reloaded = findingRepo.findById(f.getId()).orElseThrow();
        assertEquals(Map.of(), FindingFields.read(reloaded));
    }

    @Test
    void writeThenModifyThenReadReflectsLatestState() {
        Finding f = new Finding();
        f.setProjectId(seedProject());
        f.setTitle("Mutate fields");
        f.setStatusId(seedStatusId());
        f.setCreatedAt(OffsetDateTime.now());
        f.setUpdatedAt(OffsetDateTime.now());
        FindingFields.write(f, new LinkedHashMap<>(Map.of("impact", "v1")));
        findingRepo.save(f);
        em.flush();
        em.clear();

        Finding reloaded = findingRepo.findById(f.getId()).orElseThrow();
        Map<String, String> fields = FindingFields.read(reloaded);
        fields.put("impact", "v2");
        fields.put("evidence", "screenshot.png");
        FindingFields.write(reloaded, fields);
        findingRepo.save(reloaded);
        em.flush();
        em.clear();

        Finding reloadedAgain = findingRepo.findById(f.getId()).orElseThrow();
        assertEquals(Map.of("impact", "v2", "evidence", "screenshot.png"), FindingFields.read(reloadedAgain));
    }
}
