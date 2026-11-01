package com.martecyber.ares.assets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/** Real-Postgres coverage for the grouped per-type counts (same out-of-band container convention
 *  as the PostgresSpecificationCompiler*IT tests). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AssetTypeCountsIT {

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
    private AssetRepository assetRepository;

    @PersistenceContext
    private EntityManager em;

    private long orgId;
    private long otherOrgId;
    private long projectId;

    @BeforeEach
    void seed() {
        orgId      = insertOrg("Type Count Org");
        otherOrgId = insertOrg("Other Org");
        projectId  = ((Number) em.createNativeQuery(
                "INSERT INTO ares.project(organization_id, name) VALUES (:orgId, 'Type Count Project') RETURNING id")
            .setParameter("orgId", orgId)
            .getSingleResult()).longValue();

        Asset host1 = save(orgId, "host", "web01");
        save(orgId, "host", "web02");
        Asset dom   = save(orgId, "domain", "example.com");
        save(orgId, "email", "alice@example.com");
        save(otherOrgId, "host", "foreign-host");

        link(host1);
        link(dom);
    }

    private long insertOrg(String name) {
        return ((Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES (:name, 'type-count-' || floor(random()*1e9)::text) RETURNING id")
            .setParameter("name", name)
            .getSingleResult()).longValue();
    }

    private Asset save(long org, String type, String identifier) {
        Asset a = new Asset();
        a.setOrganizationId(org);
        a.setCode(identifier.toUpperCase() + "-" + System.nanoTime());
        a.setType(type);
        a.setIdentifier(identifier);
        a.setMetadata("{}");
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return assetRepository.saveAndFlush(a);
    }

    private void link(Asset a) {
        em.createNativeQuery("INSERT INTO ares.project_asset_access(project_id, asset_id, scope_status, scope_override) "
                + "VALUES (:p, :a, 'indeterminate', false)")
            .setParameter("p", projectId)
            .setParameter("a", a.getId())
            .executeUpdate();
    }

    private static Map<String, Long> toMap(List<AssetRepository.TypeCountRow> rows) {
        return rows.stream().collect(Collectors.toMap(AssetRepository.TypeCountRow::getType, AssetRepository.TypeCountRow::getCount));
    }

    @Test
    void organizationCountsGroupByTypeAndExcludeOtherOrgs() {
        assertEquals(Map.of("host", 2L, "domain", 1L, "email", 1L),
            toMap(assetRepository.countByTypeForOrganization(orgId)));
    }

    @Test
    void projectCountsOnlyIncludeAssetsLinkedToTheProject() {
        assertEquals(Map.of("host", 1L, "domain", 1L),
            toMap(assetRepository.countByTypeForProject(projectId)));
    }
}
