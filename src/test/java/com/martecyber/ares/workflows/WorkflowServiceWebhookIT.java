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
 * Exercises {@link WorkflowService}'s webhook-endpoint lifecycle against real Postgres — the
 * genuinely subtle part being that {@code webhook_endpoint} rows must survive a
 * {@code syncTriggers} resync untouched (token/secret stability), unlike {@code workflow_trigger}
 * rows themselves, which are fully deleted and reinserted on every save (see V147's migration
 * comment and {@code WorkflowService.provisionWebhookIfAbsent}'s doc comment).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkflowServiceWebhookIT {

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

    private Map<String, Object> graphWithWebhookNode(String nodeId) {
        return Map.of(
            "nodes", List.of(Map.of("id", nodeId, "type", "TRIGGER_WEBHOOK", "data", Map.of("label", "hook", "config", Map.of()))),
            "edges", List.of());
    }

    @Test
    void savingAWorkflowWithAWebhookNodeProvisionsAnEndpoint() {
        Workflow wf = service.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            "wh test", null, graphWithWebhookNode("wh1"), null).workflow();

        var info = service.getWebhookInfo(wf.getId(), "wh1");
        assertNotNull(info.token());
        assertFalse(info.token().isBlank());
        assertNotNull(info.secret());
        assertFalse(info.secret().isBlank());
        assertTrue(info.enabled());
        assertEquals(0, info.requestCount());
    }

    @Test
    void resavingTheSameWorkflowDoesNotRotateTheTokenOrSecret() {
        Workflow wf = service.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            "wh test", null, graphWithWebhookNode("wh1"), null).workflow();
        var before = service.getWebhookInfo(wf.getId(), "wh1");

        service.update(wf.getId(), null, "updated desc", null, graphWithWebhookNode("wh1"));
        var after = service.getWebhookInfo(wf.getId(), "wh1");

        assertEquals(before.token(), after.token());
        assertEquals(before.secret(), after.secret());
    }

    @Test
    void removingTheWebhookNodeDeletesItsEndpoint() {
        Workflow wf = service.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            "wh test", null, graphWithWebhookNode("wh1"), null).workflow();
        assertTrue(webhookRepo.findByWorkflowIdAndNodeId(wf.getId(), "wh1").isPresent());

        Map<String, Object> emptyGraph = Map.of(
            "nodes", List.of(Map.of("id", "t1", "type", "TRIGGER_MANUAL", "data", Map.of("label", "start", "config", Map.of()))),
            "edges", List.of());
        service.update(wf.getId(), null, null, null, emptyGraph);

        assertTrue(webhookRepo.findByWorkflowIdAndNodeId(wf.getId(), "wh1").isEmpty());
    }

    @Test
    void regenerateSecretKeepsTheTokenButChangesTheSecret() {
        Workflow wf = service.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            "wh test", null, graphWithWebhookNode("wh1"), null).workflow();
        var before = service.getWebhookInfo(wf.getId(), "wh1");

        var after = service.regenerateWebhookSecret(wf.getId(), "wh1");

        assertEquals(before.token(), after.token());
        assertNotEquals(before.secret(), after.secret());
    }

    @Test
    void twoDifferentWebhookNodesOnTheSameWorkflowGetIndependentEndpoints() {
        Map<String, Object> graph = Map.of(
            "nodes", List.of(
                Map.of("id", "wh1", "type", "TRIGGER_WEBHOOK", "data", Map.of("label", "hook1", "config", Map.of())),
                Map.of("id", "wh2", "type", "TRIGGER_WEBHOOK", "data", Map.of("label", "hook2", "config", Map.of()))),
            "edges", List.of());
        Workflow wf = service.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID, "wh test", null, graph, null).workflow();

        var info1 = service.getWebhookInfo(wf.getId(), "wh1");
        var info2 = service.getWebhookInfo(wf.getId(), "wh2");
        assertNotEquals(info1.token(), info2.token());
        assertNotEquals(info1.secret(), info2.secret());
    }
}
