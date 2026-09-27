package com.martecyber.ares.aql.compile;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.aql.registry.FieldDefinitionRepository;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises PostgresSpecificationCompiler's JSONB_PATH branch (AQL implementation plan, Phase 3)
 * against a real, fully-migrated Postgres instance — mirrors
 * PostgresSpecificationCompilerDetectionIT's structure/rationale. {@code metadata.interfaceType}
 * is the only JSONB_PATH field seeded by V142, so it doubles as this test's main subject.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PostgresSpecificationCompilerAssetIT {

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

    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;

    @PersistenceContext
    private EntityManager em;

    // Can't be a field initializer — @Autowired fields aren't populated yet when instance field
    // initializers run (mirrors DetectionAqlRegistry's precedent in the sibling Detection IT).
    private AssetAqlRegistry registry;
    private PostgresSpecificationCompiler<Asset> compiler;

    private long organizationId;

    @BeforeEach
    void seed() {
        registry = new AssetAqlRegistry(fieldDefinitionRepository);
        compiler = new PostgresSpecificationCompiler<>(registry);
        assetRepository.deleteAll();

        Number orgId = (Number) em.createNativeQuery(
                "INSERT INTO ares.organization(name, slug) VALUES ('AQL Asset Test Org', 'aql-asset-test-org-' || floor(random()*1e9)::text) RETURNING id")
            .getSingleResult();
        this.organizationId = orgId.longValue();

        assetRepository.save(asset("eth0", "interface", "{\"interfaceType\":\"ethernet\"}"));
        assetRepository.save(asset("wlan0", "interface", "{\"interfaceType\":\"wireless\"}"));
        assetRepository.save(asset("web01.internal", "host", "{}"));
        assetRepository.save(asset("10.0.0.5:80/tcp", "service", "{\"port\":80,\"protocol\":\"tcp\",\"product\":\"nginx\",\"version\":\"1.25.3\"}"));
        assetRepository.save(asset("10.0.0.5:443/tcp", "service", "{\"port\":443,\"protocol\":\"tcp\"}"));

        Asset server = asset("db01.internal", "host", "{}");
        server.setHostSubtype("server");
        assetRepository.save(server);
    }

    private Asset asset(String identifier, String type, String metadataJson) {
        Asset a = new Asset();
        a.setOrganizationId(organizationId);
        a.setCode(identifier.toUpperCase() + "-" + System.nanoTime());
        a.setType(type);
        a.setIdentifier(identifier);
        a.setMetadata(metadataJson);
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return a;
    }

    private List<Asset> run(String aql) {
        Specification<Asset> spec = compiler.compile(com.martecyber.ares.aql.parser.AqlParser.parse(aql));
        List<Asset> results = assetRepository.findAll(spec);
        results.sort(Comparator.comparing(Asset::getIdentifier));
        return results;
    }

    @Test
    void filtersByPhysicalColumnEquality() {
        // Two "host" fixtures now (web01.internal, and db01.internal added for the host.subtype
        // alias tests below) — sorted by identifier, db01 sorts before web01.
        List<Asset> results = run("type == host");
        assertEquals(2, results.size());
        assertEquals("db01.internal", results.get(0).getIdentifier());
        assertEquals("web01.internal", results.get(1).getIdentifier());
    }

    @Test
    void jsonbPathEqualityMatchesTypedMetadataKey() {
        List<Asset> results = run("metadata.interfaceType == ethernet");
        assertEquals(1, results.size());
        assertEquals("eth0", results.get(0).getIdentifier());
    }

    @Test
    void jsonbPathIsAbsentOnAssetsWithoutTheKey() {
        assertEquals(0, run("metadata.interfaceType == ethernet AND type == host").size());
    }

    @Test
    void combinesJsonbPathWithPhysicalColumn() {
        List<Asset> results = run("type == interface AND metadata.interfaceType == wireless");
        assertEquals(1, results.size());
        assertEquals("wlan0", results.get(0).getIdentifier());
    }

    @Test
    void unknownFieldIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
            com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
            () -> run("notAField == foo"));
    }

    @Test
    void bareTermSearchesDefaultFields() {
        List<Asset> results = run("web01");
        assertEquals(1, results.size());
        assertEquals("web01.internal", results.get(0).getIdentifier());
    }

    // ── type-prefixed field_definition aliases (asset_type-scoped rows get BOTH names) ──────────

    @Test
    void typePrefixedFieldNameMatchesTheSameRowsAsTheLegacyMetadataName() {
        assertEquals(run("metadata.interfaceType == wireless"), run("interface.interfaceType == wireless"));
        assertEquals(1, run("interface.interfaceType == wireless").size());
        assertEquals("wlan0", run("interface.interfaceType == wireless").get(0).getIdentifier());
    }

    @Test
    void hostSubtypeIsQueryableUnderBothItsLegacyAndTypePrefixedName() {
        assertEquals(run("hostSubtype == server"), run("host.subtype == server"));
        assertEquals(1, run("host.subtype == server").size());
        assertEquals("db01.internal", run("host.subtype == server").get(0).getIdentifier());
    }

    // ── service.* (V186: port/protocol/product/version) ────────────────────────────────────────

    @Test
    void serviceFieldsFilterByPortAndProtocol() {
        List<Asset> results = run("service.port == 80 AND service.protocol == TCP");
        assertEquals(1, results.size());
        assertEquals("10.0.0.5:80/tcp", results.get(0).getIdentifier());
    }

    @Test
    void servicePortSupportsRangeOperators() {
        assertEquals(2, run("type == service AND service.port > 1").size());
        assertEquals(1, run("service.port >= 443").size());
    }

    @Test
    void serviceProductAndVersionAreAbsentOnServicesLackingThem() {
        assertEquals(1, run("service.product == nginx").size());
        assertEquals(0, run("service.product == nginx AND service.port == 443").size());
    }
}
