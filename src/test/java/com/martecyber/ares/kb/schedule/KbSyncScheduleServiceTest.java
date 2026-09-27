package com.martecyber.ares.kb.schedule;

import com.martecyber.ares.workflows.ManagedWorkflowService;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowRunRepository;
import com.martecyber.ares.workflows.WorkflowScope;
import com.martecyber.ares.workflows.WorkflowService;
import com.martecyber.ares.workflows.WorkflowTrigger;
import com.martecyber.ares.workflows.WorkflowTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KbSyncScheduleServiceTest {

    private ManagedWorkflowService managed;
    private WorkflowService workflowService;
    private WorkflowRunRepository runRepo;
    private KbSyncScheduleService service;

    @BeforeEach
    void setUp() {
        managed = mock(ManagedWorkflowService.class);
        workflowService = mock(WorkflowService.class);
        runRepo = mock(WorkflowRunRepository.class);
        service = new KbSyncScheduleService(managed, workflowService, runRepo);
    }

    // Workflow.id has no setter (generated PK) — a mock stands in wherever a specific id value
    // is needed, same as the Job mocks in KbSyncIntegrationActionHandlerTest.
    private Workflow workflow(Long id, String managedBy) {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn(id);
        when(wf.getScopeKind()).thenReturn(WorkflowScope.PLATFORM);
        when(wf.getScopeId()).thenReturn(WorkflowScope.PLATFORM_SCOPE_ID);
        when(wf.getManagedBy()).thenReturn(managedBy);
        when(wf.isLocked()).thenReturn(true);
        when(wf.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return wf;
    }

    @SuppressWarnings("unchecked")
    @Test
    void createBuildsAPlatformScopedCronPlusIntegrationCallGraphAndTagsByManagedBy() {
        Workflow created = workflow(5L, "kb-sync:cve_update");
        when(managed.create(eq("kb-sync:cve_update"), eq(WorkflowScope.PLATFORM), eq(WorkflowScope.PLATFORM_SCOPE_ID),
            any(), any(), any())).thenReturn(created);
        when(workflowService.triggersFor(5L)).thenReturn(List.of());
        when(runRepo.findByWorkflowIdOrderByStartedAtDesc(eq(5L), any())).thenReturn(Page.empty());

        KbSyncScheduleDto dto = service.create("cve_update", "0 3 * * *");

        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(managed).create(eq("kb-sync:cve_update"), eq(WorkflowScope.PLATFORM), eq(WorkflowScope.PLATFORM_SCOPE_ID),
            any(), any(), graphCaptor.capture());
        Map<String, Object> graph = graphCaptor.getValue();
        var nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertEquals(2, nodes.size());
        assertEquals("TRIGGER_CRON", nodes.get(0).get("type"));
        var triggerConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(0).get("data")).get("config");
        assertEquals("0 3 * * *", triggerConfig.get("cronExpression"));
        assertEquals("ACTION_INTEGRATION_CALL", nodes.get(1).get("type"));
        var actionConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(1).get("data")).get("config");
        assertEquals("kb-sync", actionConfig.get("integrationType"));
        assertEquals("cve_update", actionConfig.get("action"));

        assertEquals(5L, dto.id());
        assertEquals("cve_update", dto.syncType());
    }

    @Test
    void createRejectsAnInvalidCronExpressionWithoutCallingManagedWorkflowService() {
        assertThrows(IllegalArgumentException.class, () -> service.create("cve_update", "not a cron"));
        verifyNoInteractions(managed);
    }

    @Test
    void createRejectsAnUnknownSyncType() {
        assertThrows(IllegalArgumentException.class, () -> service.create("not_a_real_type", "0 3 * * *"));
        verifyNoInteractions(managed);
    }

    @Test
    void listExtractsSyncTypeFromManagedByAndFieldsFromTheCronTriggerAndLatestRun() {
        Workflow wf = workflow(7L, "kb-sync:cwe");
        when(managed.findAllTagged("kb-sync:cwe", WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID))
            .thenReturn(List.of(wf));

        WorkflowTrigger trigger = new WorkflowTrigger();
        trigger.setTriggerType(WorkflowTriggerType.CRON);
        trigger.setConfig("{\"cronExpression\":\"0 4 * * *\"}");
        trigger.setEnabled(true);
        trigger.setNextRunAt(OffsetDateTime.parse("2026-02-01T04:00:00Z"));
        when(workflowService.triggersFor(7L)).thenReturn(List.of(trigger));

        var lastRun = mock(com.martecyber.ares.workflows.WorkflowRun.class);
        when(lastRun.getStartedAt()).thenReturn(OffsetDateTime.parse("2026-01-15T04:00:00Z"));
        when(runRepo.findByWorkflowIdOrderByStartedAtDesc(eq(7L), any())).thenReturn(new PageImpl<>(List.of(lastRun)));

        List<KbSyncScheduleDto> result = service.list("cwe");

        assertEquals(1, result.size());
        KbSyncScheduleDto dto = result.get(0);
        assertEquals(7L, dto.id());
        assertEquals("cwe", dto.syncType());
        assertEquals("0 4 * * *", dto.cronExpression());
        assertTrue(dto.enabled());
        assertEquals(OffsetDateTime.parse("2026-02-01T04:00:00Z"), dto.nextRunAt());
        assertEquals(OffsetDateTime.parse("2026-01-15T04:00:00Z"), dto.lastRunAt());
    }

    @Test
    void setEnabledDelegatesToManagedWorkflowServiceAndReturnsTheUpdatedDto() {
        Workflow wf = workflow(9L, "kb-sync:capec");
        when(workflowService.get(9L)).thenReturn(wf);
        when(workflowService.triggersFor(9L)).thenReturn(List.of());
        when(runRepo.findByWorkflowIdOrderByStartedAtDesc(eq(9L), any())).thenReturn(Page.empty());

        KbSyncScheduleDto dto = service.setEnabled(9L, false);

        verify(managed).setEnabled(9L, false);
        assertEquals(9L, dto.id());
    }

    @Test
    void deleteDelegatesToManagedWorkflowService() {
        service.delete(3L);
        verify(managed).delete(3L);
    }
}
