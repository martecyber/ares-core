package com.martecyber.ares.workflows.templates;

import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowGraphValidator;
import com.martecyber.ares.workflows.WorkflowRepository;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/** Real Postgres-backed CRUD/export/import/createFromWorkflow coverage — both repositories
 *  involved (WorkflowTemplateRepository, WorkflowRepository) are plain JPA, so unlike most
 *  workflow ITs there's nothing worth mocking here. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkflowTemplateServiceIT {

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
    private WorkflowTemplateRepository repo;

    @Autowired
    private WorkflowRepository workflowRepo;

    private WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        WorkflowGraphValidator graphValidator = new WorkflowGraphValidator(
            new AqlRegistryLookup(List.of()), new AgentToolSpecRegistry(),
            new IntegrationActionRegistry(List.of(), mock(com.martecyber.ares.plugins.PluginRepository.class)),
            mock(com.martecyber.ares.agents.pools.AgentPoolMemberRepository.class),
            mock(com.martecyber.ares.agents.AgentRepository.class));
        service = new WorkflowTemplateService(repo, workflowRepo, graphValidator);
    }

    /** A minimal but structurally valid graph (trigger node present) — every create/update now
     *  goes through the same {@link com.martecyber.ares.workflows.WorkflowGraphValidator} a real
     *  {@code Workflow} does (draft mode, see {@link WorkflowTemplateService#validate}), so an
     *  empty {@code {"nodes":[],"edges":[]}} graph (missing a trigger) is no longer accepted. */
    private static final String SIMPLE_GRAPH = "{\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_MANUAL\","
        + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"label\":\"start\",\"config\":{}}}],\"edges\":[]}";

    @Test
    void createsAndFetchesATemplate() {
        var created = service.create(new WorkflowTemplateService.CreateOrUpdateRequest(
            "My template", "desc", "platform", SIMPLE_GRAPH));
        assertNotNull(created.id());

        var fetched = service.get(created.id());
        assertEquals("My template", fetched.name());
        assertEquals("desc", fetched.description());
        assertEquals("platform", fetched.scopeKind());
        assertEquals(SIMPLE_GRAPH, fetched.graphDefinition());
    }

    @Test
    void createRejectsBlankName() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.create(
            new WorkflowTemplateService.CreateOrUpdateRequest("  ", null, "platform", SIMPLE_GRAPH)));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createRejectsInvalidGraphJson() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.create(
            new WorkflowTemplateService.CreateOrUpdateRequest("t", null, "platform", "not json")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createRejectsUnknownScopeKind() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.create(
            new WorkflowTemplateService.CreateOrUpdateRequest("t", null, "bogus", SIMPLE_GRAPH)));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createRejectsAProjectOnlyNodeAtPlatformScope() {
        String graph = "{\"nodes\":["
            + "{\"id\":\"t1\",\"type\":\"TRIGGER_MANUAL\",\"position\":{\"x\":0,\"y\":0},\"data\":{\"label\":\"start\",\"config\":{}}},"
            + "{\"id\":\"n1\",\"type\":\"ACTION_AGENT_TASK\",\"position\":{\"x\":0,\"y\":0},\"data\":{\"label\":\"Scan\",\"config\":{\"tool\":\"nmap\"}}}"
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"t1\",\"target\":\"n1\"}]}";
        // Thrown straight out of WorkflowGraphValidator, same as it would for a real Workflow —
        // GlobalExceptionHandler is what maps this to an HTTP 400 at the controller boundary.
        assertThrows(com.martecyber.ares.workflows.WorkflowValidationException.class, () -> service.create(
            new WorkflowTemplateService.CreateOrUpdateRequest("t", null, "platform", graph)));
    }

    @Test
    void updatesAnExistingTemplate() {
        var created = service.create(new WorkflowTemplateService.CreateOrUpdateRequest(
            "Original", null, "platform", SIMPLE_GRAPH));
        var updated = service.update(created.id(), new WorkflowTemplateService.CreateOrUpdateRequest(
            "Renamed", "now with a description", "organization", SIMPLE_GRAPH));
        assertEquals("Renamed", updated.name());
        assertEquals("now with a description", updated.description());
        assertEquals("organization", updated.scopeKind());
    }

    @Test
    void deletesATemplate() {
        var created = service.create(new WorkflowTemplateService.CreateOrUpdateRequest(
            "Gone soon", null, "platform", SIMPLE_GRAPH));
        service.delete(created.id());
        assertThrows(NotFoundException.class, () -> service.get(created.id()));
    }

    @Test
    void deletingAnUnknownIdThrows() {
        assertThrows(NotFoundException.class, () -> service.delete(999_999L));
    }

    @Test
    void listsTemplatesNewestFirst() {
        service.create(new WorkflowTemplateService.CreateOrUpdateRequest("First", null, "platform", SIMPLE_GRAPH));
        service.create(new WorkflowTemplateService.CreateOrUpdateRequest("Second", null, "platform", SIMPLE_GRAPH));
        var page = service.list(0, 50);
        assertTrue(page.getContent().size() >= 2);
        assertEquals("Second", page.getContent().get(0).name());
    }

    @Test
    void exportOneRoundTripsIntoAnEquivalentImportedTemplate() {
        var created = service.create(new WorkflowTemplateService.CreateOrUpdateRequest(
            "Exportable", "desc", "platform", SIMPLE_GRAPH));
        var exported = service.exportOne(created.id());
        assertEquals("Exportable", exported.name());
        assertEquals("platform", exported.scopeKind());
        assertEquals(SIMPLE_GRAPH, exported.graphDefinition());
    }

    @Test
    void createFromWorkflowStripsScopeBoundIdsAndFallsBackToWorkflowName() {
        Workflow wf = new Workflow();
        wf.setScopeKind("project");
        wf.setScopeId(1L);
        wf.setName("Source workflow");
        wf.setGraphDefinition("{\"nodes\":["
            + "{\"id\":\"t1\",\"type\":\"TRIGGER_MANUAL\",\"position\":{\"x\":0,\"y\":0},\"data\":{\"label\":\"start\",\"config\":{}}},"
            + "{\"id\":\"n1\",\"type\":\"ACTION_AGENT_TASK\",\"position\":{\"x\":0,\"y\":0},"
            + "\"data\":{\"label\":\"Scan\",\"config\":{\"poolId\":42,\"tool\":\"nmap\"}}}"
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"t1\",\"target\":\"n1\"}]}");
        wf.setStatus("active");
        wf.setCreatedAt(OffsetDateTime.now());
        wf.setUpdatedAt(OffsetDateTime.now());
        wf = workflowRepo.save(wf);

        var template = service.createFromWorkflow(wf.getId(), null);
        assertEquals("Source workflow", template.name());
        assertEquals("project", template.scopeKind());
        assertFalse(template.graphDefinition().contains("poolId"));
        assertTrue(template.graphDefinition().contains("\"tool\":\"nmap\""));
    }

    @Test
    void createFromWorkflowHonorsNameOverride() {
        Workflow wf = new Workflow();
        wf.setScopeKind("project");
        wf.setScopeId(1L);
        wf.setName("Source workflow");
        wf.setGraphDefinition(SIMPLE_GRAPH);
        wf.setStatus("active");
        wf.setCreatedAt(OffsetDateTime.now());
        wf.setUpdatedAt(OffsetDateTime.now());
        wf = workflowRepo.save(wf);

        var template = service.createFromWorkflow(wf.getId(),
            new WorkflowTemplateService.FromWorkflowRequest("Custom name", "custom desc", "organization"));
        assertEquals("Custom name", template.name());
        assertEquals("custom desc", template.description());
        assertEquals("organization", template.scopeKind());
    }

    @Test
    void createFromWorkflowRejectsUnknownWorkflow() {
        assertThrows(NotFoundException.class,
            () -> service.createFromWorkflow(999_999L, null));
    }

    @Test
    void exportZipContainsOneEntryPerTemplate() throws Exception {
        var t1 = service.create(new WorkflowTemplateService.CreateOrUpdateRequest("Zip one", null, "platform", SIMPLE_GRAPH));
        var t2 = service.create(new WorkflowTemplateService.CreateOrUpdateRequest("Zip two", null, "platform", SIMPLE_GRAPH));
        byte[] zip = service.exportZip(List.of(t1.id(), t2.id()));

        int entries = 0;
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            while (zis.getNextEntry() != null) entries++;
        }
        assertEquals(2, entries);
    }
}
