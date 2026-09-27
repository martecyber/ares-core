package com.martecyber.ares.integrations.schedule;

import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.integrations.dto.IntegrationDto;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowSaveResult;
import com.martecyber.ares.workflows.WorkflowScope;
import com.martecyber.ares.workflows.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationScheduleServiceTest {

    private WorkflowService workflowService;
    private IntegrationService integrationService;
    private IntegrationScheduleService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        integrationService = mock(IntegrationService.class);
        service = new IntegrationScheduleService(workflowService, integrationService);
    }

    private Workflow workflow(Long id) {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn(id);
        return wf;
    }

    private IntegrationDto integration(Long id, String type) {
        return new IntegrationDto(id, null, "test-" + id, type, "project", 42L, "active", "connected",
            null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    @Test
    void createBuildsAProjectScopedCronPlusIntegrationCallGraphAndActivatesItUnlocked() {
        when(integrationService.get(9L)).thenReturn(integration(9L, "tenable"));
        Workflow created = workflow(5L);
        when(workflowService.create(eq(WorkflowScope.PROJECT), eq(42L), any(), any(), any(), isNull()))
            .thenReturn(new WorkflowSaveResult(created, List.of()));

        service.create(42L, 9L, "SYNC_ASSETS", "0 2 * * *");

        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).create(eq(WorkflowScope.PROJECT), eq(42L), any(), any(), graphCaptor.capture(), isNull());
        var nodes = (List<Map<String, Object>>) graphCaptor.getValue().get("nodes");
        assertEquals("TRIGGER_CRON", nodes.get(0).get("type"));
        assertEquals("ACTION_INTEGRATION_CALL", nodes.get(1).get("type"));
        var actionConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(1).get("data")).get("config");
        assertEquals("tenable", actionConfig.get("integrationType"));
        assertEquals(9L, actionConfig.get("integrationId"));
        assertEquals("SYNC_ASSETS", actionConfig.get("action"));
        assertFalse(actionConfig.containsKey("paramsTemplate"));

        // Unlike the old locked/ManagedWorkflowService path, the workflow this creates must be
        // immediately editable — flipped straight to active via the plain WorkflowService, never
        // routed through ManagedWorkflowService's lock.
        verify(workflowService).update(5L, null, null, "active", null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void createForEnrichAssetsAlwaysSetsTargetSourceToScopeMatchingTheOldHardcodedBehavior() {
        when(integrationService.get(11L)).thenReturn(integration(11L, "shodan"));
        Workflow created = workflow(6L);
        when(workflowService.create(any(), any(), any(), any(), any(), isNull())).thenReturn(new WorkflowSaveResult(created, List.of()));

        service.create(42L, 11L, "ENRICH_ASSETS", "0 */4 * * *");

        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).create(any(), any(), any(), any(), graphCaptor.capture(), isNull());
        var nodes = (List<Map<String, Object>>) graphCaptor.getValue().get("nodes");
        var actionConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(1).get("data")).get("config");
        var paramsTemplate = (Map<String, Object>) actionConfig.get("paramsTemplate");
        assertEquals("scope", paramsTemplate.get("targetSource"));
    }

    @Test
    void createRejectsAnInvalidCronExpression() {
        assertThrows(IllegalArgumentException.class, () -> service.create(42L, 9L, "SYNC_ASSETS", "garbage"));
        verifyNoInteractions(workflowService);
    }
}
