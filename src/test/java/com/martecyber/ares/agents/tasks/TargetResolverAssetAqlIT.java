package com.martecyber.ares.agents.tasks;

import com.martecyber.ares.aql.registry.FieldDefinitionRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.projects.ProjectAssetAccess;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises {@link TargetResolver}'s new {@code asset_aql} selector — resolving a Workflow
 * ACTION_AGENT_TASK node's {@code targetsFrom} against real Postgres-backed Asset/
 * ProjectAssetAccess data (mirrors the AQL compiler IT suites' structure/rationale).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TargetResolverAssetAqlIT {

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
    @Autowired private ProjectAssetAccessRepository projectAssetAccessRepo;
    @Autowired private FieldDefinitionRepository fieldDefinitionRepo;
    @Autowired private com.martecyber.ares.projects.ProjectRepository projectRepo;
    @Autowired private com.martecyber.ares.organizations.OrganizationRepository organizationRepo;

    private TargetResolver resolver;
    private Long projectId;
    private Long otherProjectId;

    @BeforeEach
    void setUp() {
        AssetAqlRegistry aqlRegistry = new AssetAqlRegistry(fieldDefinitionRepo);
        resolver = new TargetResolver(null, assetRepo, aqlRegistry);

        var now = OffsetDateTime.now();
        var org = new com.martecyber.ares.organizations.Organization();
        org.setName("TR Test Org " + System.nanoTime());
        org.setSlug("tr-test-org-" + System.nanoTime());
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        Long orgId = organizationRepo.save(org).getId();

        projectId = newProject(orgId, now);
        otherProjectId = newProject(orgId, now);

        Long hostId = newAsset(orgId, "host", "10.0.0.1", now);
        Long domainId = newAsset(orgId, "domain", "example.com", now);
        Long serviceId = newAsset(orgId, "service", "10.0.0.1:443/tcp", now);
        Long otherProjectHostId = newAsset(orgId, "host", "10.0.0.99", now);

        grant(projectId, hostId);
        grant(projectId, domainId);
        grant(projectId, serviceId);
        grant(otherProjectId, otherProjectHostId);
    }

    private Long newProject(Long orgId, OffsetDateTime now) {
        var project = new com.martecyber.ares.projects.Project();
        project.setOrganizationId(orgId);
        project.setName("TR Test Project " + System.nanoTime());
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        return projectRepo.save(project).getId();
    }

    private Long newAsset(Long orgId, String type, String identifier, OffsetDateTime now) {
        Asset a = new Asset();
        a.setOrganizationId(orgId);
        a.setType(type);
        a.setIdentifier(identifier);
        a.setCode("AST-" + System.nanoTime());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return assetRepo.save(a).getId();
    }

    private void grant(Long projectId, Long assetId) {
        ProjectAssetAccess paa = new ProjectAssetAccess();
        paa.setProjectId(projectId);
        paa.setAssetId(assetId);
        paa.setScopeStatus("in_scope");
        projectAssetAccessRepo.save(paa);
    }

    private List<String> resolve(Long projectId, Map<String, Object> selector) {
        return resolver.resolve(projectId, selector);
    }

    @Test
    void resolvesUnrestrictedAqlAgainstProjectScopedAssets() {
        List<String> out = resolve(projectId, Map.of("type", "asset_aql", "aql", "type == host"));
        assertEquals(List.of("10.0.0.1"), out);
    }

    @Test
    void restrictsToToolsAssetTypesEvenWhenQueryDoesNotMentionType() {
        // Query alone would match domain + host; assetTypes narrows to just domain.
        List<String> out = resolve(projectId, Map.of(
            "type", "asset_aql", "aql", "identifier ~= example",
            "assetTypes", List.of("domain")));
        assertEquals(List.of("example.com"), out);
    }

    @Test
    void serviceIdentifierIsReshapedDroppingProtocolSuffix() {
        List<String> out = resolve(projectId, Map.of("type", "asset_aql", "aql", "type == service"));
        assertEquals(List.of("10.0.0.1:443"), out);
    }

    @Test
    void invalidAssetTypesAreSilentlyDroppedNotTrustedIntoTheQuery() {
        // A bogus assetTypes entry (e.g. tampered/stale config) must not be concatenated
        // verbatim into the composed AQL string — it's filtered against the canonical set.
        List<String> out = resolve(projectId, Map.of(
            "type", "asset_aql", "aql", "type == host",
            "assetTypes", List.of("host", "not-a-real-type) OR (1==1")));
        assertEquals(List.of("10.0.0.1"), out);
    }

    @Test
    void neverReturnsAssetsFromAnotherProject() {
        List<String> out = resolve(projectId, Map.of("type", "asset_aql", "aql", "type == host"));
        assertFalse(out.contains("10.0.0.99"));
    }

    @Test
    void missingAqlIsRejected() {
        assertThrows(ResponseStatusException.class,
            () -> resolve(projectId, Map.of("type", "asset_aql")));
    }

    @Test
    void resolveIntoReplacesTargetsFromWithResolvedTargets() {
        Map<String, Object> args = Map.of("targetsFrom", Map.of("type", "asset_aql", "aql", "type == host"));
        Map<String, Object> out = resolver.resolveInto(projectId, args);
        assertFalse(out.containsKey("targetsFrom"));
        assertEquals(List.of("10.0.0.1"), out.get("targets"));
    }
}
