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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Regression coverage for the {@code workflow_step_run_workflow_run_id_fkey} production incident:
 * {@code workflow_run}/{@code workflow_step_run} cascade-delete from {@code workflow} purely at
 * the DB level (V146) — Hibernate has no visibility into it — so deleting a workflow with an
 * active run raced against {@link WorkflowRunService#advance}'s split {@code REQUIRES_NEW}
 * transactions (read the run, then later insert a new step row) and surfaced as a raw FK
 * violation instead of a clear error. {@link WorkflowService#delete}/{@code deleteManaged} now
 * refuse up front instead. Real Postgres, same setup shape as {@link WorkflowServiceWebhookIT}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkflowServiceDeleteGuardIT {

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

    private Map<String, Object> simpleTriggerGraph() {
        return Map.of(
            "nodes", List.of(Map.of("id", "t1", "type", "TRIGGER_MANUAL", "data", Map.of("label", "start", "config", Map.of()))),
            "edges", List.of());
    }

    private WorkflowRun runWithStatus(Long workflowId, String status) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(workflowId);
        run.setWorkflowVersion(1);
        run.setGraphSnapshot("{}");
        run.setTriggerNodeId("t1");
        run.setStatus(status);
        run.setContext("{}");
        run.setStartedAt(OffsetDateTime.now());
        return runRepo.save(run);
    }

    @Test
    void deleteIsRejectedWhileARunIsPending() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "wf", null, simpleTriggerGraph(), null).workflow();
        runWithStatus(wf.getId(), WorkflowRunStatus.PENDING);

        var ex = assertThrows(WorkflowValidationException.class, () -> service.delete(wf.getId()));
        assertTrue(ex.getMessage().contains("in progress"));
        assertTrue(workflowRepo.existsById(wf.getId()));
    }

    @Test
    void deleteIsRejectedWhileARunIsRunning() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "wf", null, simpleTriggerGraph(), null).workflow();
        runWithStatus(wf.getId(), WorkflowRunStatus.RUNNING);

        assertThrows(WorkflowValidationException.class, () -> service.delete(wf.getId()));
        assertTrue(workflowRepo.existsById(wf.getId()));
    }

    @Test
    void deleteSucceedsOnceTheOnlyRunHasFinished() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "wf", null, simpleTriggerGraph(), null).workflow();
        runWithStatus(wf.getId(), WorkflowRunStatus.COMPLETED);

        service.delete(wf.getId());
        assertFalse(workflowRepo.existsById(wf.getId()));
    }

    @Test
    void deleteSucceedsWhenThereAreNoRunsAtAll() {
        Workflow wf = service.create(WorkflowScope.PROJECT, 1L, "wf", null, simpleTriggerGraph(), null).workflow();

        service.delete(wf.getId());
        assertFalse(workflowRepo.existsById(wf.getId()));
    }
}
