package com.martecyber.ares.assets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * DNS TXT records (DKIM keys, long SPF strings) exceed 255 characters; V211 widened
 * asset.identifier to TEXT so the importer stores them instead of silently dropping them.
 * Runs against the fully-migrated out-of-band Postgres, which also exercises ddl-auto=validate.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AssetLongIdentifierIT {

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

    @Autowired private AssetRepository assetRepo;
    @PersistenceContext private EntityManager em;

    @Test
    void storesAndFindsTxtRecordsLongerThan255Characters() {
        long org = ((Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('Org', ?1) RETURNING id")
            .setParameter(1, "long-id-" + System.nanoTime()).getSingleResult()).longValue();
        String dkim = "v=DKIM1; k=rsa; p=" + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A".repeat(25);
        assertTrue(dkim.length() > 255);

        Asset a = new Asset();
        a.setOrganizationId(org);
        a.setType(AssetType.TEXT_DATA);
        a.setIdentifier(dkim);
        a.setCode("TXT-" + System.nanoTime());
        a.setMetadata("{}");
        a.setCreatedAt(OffsetDateTime.now());
        assetRepo.saveAndFlush(a);
        em.clear();

        Asset found = assetRepo.findByOrganizationIdAndTypeAndIdentifier(org, AssetType.TEXT_DATA, dkim).orElseThrow();
        assertEquals(dkim, found.getIdentifier());
    }
}
