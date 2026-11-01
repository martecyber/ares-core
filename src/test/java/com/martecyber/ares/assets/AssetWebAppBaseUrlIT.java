package com.martecyber.ares.assets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/** Imports recognize a web application by any of its Base URLs, not only its identifier. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AssetWebAppBaseUrlIT {

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

    @Autowired private AssetRepository repo;
    @PersistenceContext private EntityManager em;

    @Test
    void findsAWebApplicationByASecondaryBaseUrl() {
        long org = ((Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('Org', ?1) RETURNING id")
            .setParameter(1, "webapp-" + System.nanoTime()).getSingleResult()).longValue();
        long app = ((Number) em.createNativeQuery("""
                INSERT INTO ares.asset(code, organization_id, type, identifier, metadata)
                VALUES (?1, ?2, 'web_application', 'https://new.example.com',
                        '{"baseUrls":["https://new.example.com","https://old.example.com"]}')
                RETURNING id""")
            .setParameter(1, "W-" + System.nanoTime()).setParameter(2, org).getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO ares.asset(code, organization_id, type, identifier, metadata)
                VALUES (?1, ?2, 'web_endpoint', 'https://old.example.com', '{"baseUrls":"https://old.example.com"}')""")
            .setParameter(1, "E-" + System.nanoTime()).setParameter(2, org).executeUpdate();

        assertEquals(app, repo.findWebApplicationByBaseUrl(org, "https://old.example.com").orElseThrow().getId());
        assertTrue(repo.findWebApplicationByBaseUrl(org, "https://other.example.com").isEmpty());
        assertTrue(repo.findWebApplicationByBaseUrl(org + 1, "https://old.example.com").isEmpty());
    }
}
