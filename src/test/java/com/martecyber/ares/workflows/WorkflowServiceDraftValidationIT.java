package com.martecyber.ares.workflows;

import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.common.PlatformSettingsService;
import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.webhooks.OutboundWebhookSecretRepository;
import com.martecyber.ares.webhooks.WebhookEndpointRepository;
import com.martecyber.ares.webhooks.WebhookSignatureService;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises the create/update-time {@code requireComplete} gate this feature added to
 * {@link WorkflowGraphValidator} — a workflow (including one just instantiated from a
 * {@link com.martecyber.ares.workflows.templates.WorkflowTemplate}, which has its scope-bound
 * ids intentionally stripped) must be save-able in draft/disabled status with an
 * ACTION_AGENT_TASK node still missing {@code poolId}, but rejected the moment it's activated
 * without one. Real Postgres, same setup shape as {@link WorkflowServiceWebhookIT}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkflowServiceDraftValidationIT {

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

    @Autowired private WorkflowRepository workflowRepo;
    @Autowired private WorkflowTriggerRepository triggerRepo;
    @Autowired private WebhookEndpointRepository webhookRepo;
    @Autowired private OutboundWebhookSecretRepository outboundSecretRepo;
    @Autowired private com.martecyber.ares.projects.ProjectRepository projectRepo;
    @Autowired private WorkflowRunRepository runRepo;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        var validator = new WorkflowGraphValidator(new AqlRegistryLookup(List.of()), new AgentToolSpecRegistry(),
            new IntegrationActionRegistry(List.of(), mock(com.martecyber.ares.plugins.PluginRepository.class)),
            mock(com.martecyber.ares.agents.pools.AgentPoolMemberRepository.class),
            mock(com.martecyber.ares.agents.AgentRepository.class));
        var encryption = new CredentialEncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        service = new WorkflowService(workflowRepo, triggerRepo, validator, webhookRepo,
            new WebhookSignatureService(), encryption, outboundSecretRepo, projectRepo, mock(PlatformSettingsService.class), runRepo);
    }

    private Map<String, Object> graphWithAgentTaskMissingPoolId() {
        return Map.of(
            "nodes", List.of(
                Map.of("id", "t1", "type", "TRIGGER_MANUAL", "data", Map.of("label", "start", "config", Map.of())),
                Map.of("id", "a1", "type", "ACTION_AGENT_TASK", "data", Map.of("label", "scan", "config", Map.of("tool", "nmap")))),
            "edges", List.of(Map.of("id", "e1", "source", "t1", "target", "a1")));
    }

    private Map<String, Object> graphWithAgentTask(long poolId) {
        return Map.of(
            "nodes", List.of(
                Map.of("id", "t1", "type", "TRIGGER_MANUAL", "data", Map.of("label", "start", "config", Map.of())),
                Map.of("id", "a1", "type", "ACTION_AGENT_TASK", "data", Map.of("label", "scan", "config", Map.of("tool", "nmap", "poolId", poolId)))),
            "edges", List.of(Map.of("id", "e1", "source", "t1", "target", "a1")));
    }

    @Test
    void createLandsInDraftEvenWithAnUnconfiguredAgentTaskNode() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        assertEquals("draft", wf.getStatus());
        assertFalse(wf.getGraphDefinition().contains("\"poolId\""));
    }

    @Test
    void resavingAsDraftStillAllowsTheMissingPoolId() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        Workflow updated = service.update(wf.getId(), null, null, "draft", graphWithAgentTaskMissingPoolId()).workflow();
        assertEquals("draft", updated.getStatus());
    }

    @Test
    void activatingWithTheMissingPoolIdIsRejected() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        var ex = assertThrows(WorkflowValidationException.class,
            () -> service.update(wf.getId(), null, null, "active", graphWithAgentTaskMissingPoolId()));
        assertTrue(ex.getMessage().contains("poolId"));
    }

    @Test
    void activatingAfterFillingInThePoolIdSucceeds() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        Workflow activated = service.update(wf.getId(), null, null, "active", graphWithAgentTask(42L)).workflow();
        assertEquals("active", activated.getStatus());
        assertTrue(activated.getGraphDefinition().contains("\"poolId\":42"));
    }

    /** WorkflowService.update/create surface WorkflowGraphValidator's non-blocking warnings
     *  (WorkflowSaveResult#warnings) rather than swallowing them — this fixture's mocked
     *  AgentPoolMemberRepository reports no members for pool 42, so "no agent in the pool
     *  currently reports this tool" fires but never blocks the save. */
    @Test
    void updateSurfacesTheNoPoolMemberReportsToolWarningWithoutBlockingTheSave() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        WorkflowSaveResult result = service.update(wf.getId(), null, null, "active", graphWithAgentTask(42L));
        assertEquals("active", result.workflow().getStatus());
        assertEquals(1, result.warnings().size());
        assertEquals("a1", result.warnings().get(0).nodeId());
        assertTrue(result.warnings().get(0).message().contains("nmap"));
    }

    @Test
    void statusOnlyActivationRevalidatesTheAlreadyStoredIncompleteGraph() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "incomplete", null,
            graphWithAgentTaskMissingPoolId(), null).workflow();
        // graphDefinition is null here — a bare status-only PATCH must still re-validate the
        // ALREADY-STORED graph against the new resolved status, not skip validation entirely just
        // because this particular request didn't also resend the graph.
        var ex = assertThrows(WorkflowValidationException.class,
            () -> service.update(wf.getId(), null, null, "active", null));
        assertTrue(ex.getMessage().contains("poolId"));
    }

    @Test
    void statusOnlyActivationSucceedsOnceTheStoredGraphIsAlreadyComplete() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "complete", null,
            graphWithAgentTask(42L), null).workflow();
        Workflow activated = service.update(wf.getId(), null, null, "active", null).workflow();
        assertEquals("active", activated.getStatus());
    }
}
