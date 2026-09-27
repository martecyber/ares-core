package com.martecyber.ares.workflows;

import com.martecyber.ares.agents.tasks.AgentTask;
import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.jobs.Job;
import com.martecyber.ares.jobs.JobRepository;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WorkflowStepPollerTest {

    private WorkflowStepRunRepository stepRunRepo;
    private AgentTaskRepository agentTaskRepo;
    private JobRepository jobRepo;
    private WorkflowRunService runService;
    private IntegrationActionRegistry integrationActionRegistry;
    private WorkflowStepPoller poller;

    @BeforeEach
    void setUp() {
        stepRunRepo = mock(WorkflowStepRunRepository.class);
        agentTaskRepo = mock(AgentTaskRepository.class);
        jobRepo = mock(JobRepository.class);
        runService = mock(WorkflowRunService.class);
        integrationActionRegistry = mock(IntegrationActionRegistry.class);
        poller = new WorkflowStepPoller(stepRunRepo, agentTaskRepo, jobRepo, runService, integrationActionRegistry);
        when(jobRepo.findAllById(any())).thenReturn(List.of());
        when(stepRunRepo.findByStatusAndRefType(anyString(), anyString())).thenReturn(List.of());
    }

    private WorkflowStepRun waitingStep(Long id, Long refId, String refType) {
        WorkflowStepRun step = new WorkflowStepRun();
        setId(step, id);
        step.setWorkflowRunId(100L);
        step.setNodeId("a1");
        step.setNodeType("ACTION_AGENT_TASK");
        step.setStatus(WorkflowStepStatus.WAITING);
        step.setRefType(refType);
        step.setRefId(refId);
        return step;
    }

    private void setId(WorkflowStepRun step, Long id) {
        try {
            Field f = WorkflowStepRun.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(step, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private AgentTask agentTaskWithStatus(Long id, String status) {
        AgentTask t = mock(AgentTask.class);
        when(t.getId()).thenReturn(id);
        when(t.getStatus()).thenReturn(status);
        return t;
    }

    @Test
    void completedAgentTaskAdvancesStepToCompleted() {
        WorkflowStepRun step = waitingStep(1L, 42L, "AGENT_TASK");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "AGENT_TASK")).thenReturn(List.of(step));
        AgentTask task = agentTaskWithStatus(42L, "completed");
        when(task.getExitCode()).thenReturn(0);
        when(agentTaskRepo.findAllById(any())).thenReturn(List.of(task));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.COMPLETED, step.getStatus());
        verify(stepRunRepo).save(step);
        verify(runService).advance(100L);
    }

    @Test
    void failedAgentTaskAdvancesStepToFailed() {
        WorkflowStepRun step = waitingStep(2L, 43L, "AGENT_TASK");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "AGENT_TASK")).thenReturn(List.of(step));
        AgentTask task = agentTaskWithStatus(43L, "failed");
        when(task.getError()).thenReturn("boom");
        when(agentTaskRepo.findAllById(any())).thenReturn(List.of(task));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.FAILED, step.getStatus());
        assertEquals("boom", step.getError());
        verify(runService).advance(100L);
    }

    @Test
    void nonTerminalAgentTaskDoesNotAdvance() {
        WorkflowStepRun step = waitingStep(3L, 44L, "AGENT_TASK");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "AGENT_TASK")).thenReturn(List.of(step));
        AgentTask task = agentTaskWithStatus(44L, "running");
        when(agentTaskRepo.findAllById(any())).thenReturn(List.of(task));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.WAITING, step.getStatus());
        verify(stepRunRepo, never()).save(any());
        verify(runService, never()).advance(anyLong());
    }

    private WorkflowStepRun waitingIntegrationActionStep(Long id, Long refId, String integrationType) {
        WorkflowStepRun step = waitingStep(id, refId, "INTEGRATION_ACTION");
        step.setInput("{\"integrationType\":\"" + integrationType + "\"}");
        return step;
    }

    @Test
    void completedIntegrationActionAdvancesStepToCompleted() {
        WorkflowStepRun step = waitingIntegrationActionStep(4L, 77L, "caido-api");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION")).thenReturn(List.of(step));
        when(integrationActionRegistry.isRegistered("caido-api")).thenReturn(true);
        IntegrationActionHandler handler = mock(IntegrationActionHandler.class);
        when(integrationActionRegistry.require("caido-api")).thenReturn(handler);
        when(handler.checkStatus(77L)).thenReturn(new IntegrationActionResult(IntegrationActionResult.COMPLETED, "{\"ok\":true}", null));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.COMPLETED, step.getStatus());
        assertEquals("{\"ok\":true}", step.getOutput());
        verify(stepRunRepo).save(step);
        verify(runService).advance(100L);
    }

    @Test
    void failedIntegrationActionAdvancesStepToFailed() {
        WorkflowStepRun step = waitingIntegrationActionStep(5L, 78L, "caido-api");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION")).thenReturn(List.of(step));
        when(integrationActionRegistry.isRegistered("caido-api")).thenReturn(true);
        IntegrationActionHandler handler = mock(IntegrationActionHandler.class);
        when(integrationActionRegistry.require("caido-api")).thenReturn(handler);
        when(handler.checkStatus(78L)).thenReturn(new IntegrationActionResult(IntegrationActionResult.FAILED, null, "boom"));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.FAILED, step.getStatus());
        assertEquals("boom", step.getError());
        verify(runService).advance(100L);
    }

    @Test
    void nonTerminalIntegrationActionDoesNotAdvance() {
        WorkflowStepRun step = waitingIntegrationActionStep(6L, 79L, "caido-api");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION")).thenReturn(List.of(step));
        when(integrationActionRegistry.isRegistered("caido-api")).thenReturn(true);
        IntegrationActionHandler handler = mock(IntegrationActionHandler.class);
        when(integrationActionRegistry.require("caido-api")).thenReturn(handler);
        when(handler.checkStatus(79L)).thenReturn(new IntegrationActionResult(IntegrationActionResult.RUNNING, null, null));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.WAITING, step.getStatus());
        verify(stepRunRepo, never()).save(any());
        verify(runService, never()).advance(anyLong());
    }

    @Test
    void integrationActionStepFailsImmediatelyWhenTypeIsNotRegistered() {
        WorkflowStepRun step = waitingIntegrationActionStep(9L, 81L, "tenable-mssp");
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION")).thenReturn(List.of(step));
        when(integrationActionRegistry.isRegistered("tenable-mssp")).thenReturn(false);
        when(integrationActionRegistry.missingHandlerMessage("tenable-mssp"))
            .thenReturn("Integration type 'tenable-mssp' requires the 'Tenable' plugin (tenable), which is installed but disabled.");

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.FAILED, step.getStatus());
        assertTrue(step.getError().contains("Tenable"));
        verify(integrationActionRegistry, never()).require(anyString());
        verify(stepRunRepo).save(step);
        verify(runService).advance(100L);
    }

    @Test
    void integrationActionStepWithNoStashedTypeIsSkippedNotCrashed() {
        WorkflowStepRun step = waitingStep(7L, 80L, "INTEGRATION_ACTION"); // no input set
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION")).thenReturn(List.of(step));

        poller.pollWaitingSteps();

        assertEquals(WorkflowStepStatus.WAITING, step.getStatus());
        verifyNoInteractions(integrationActionRegistry);
        verify(runService, never()).advance(anyLong());
    }

    @Test
    void noWaitingStepsIsANoOp() {
        when(stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "AGENT_TASK")).thenReturn(List.of());
        poller.pollWaitingSteps();
        verifyNoInteractions(agentTaskRepo);
        verify(runService, never()).advance(anyLong());
    }
}
