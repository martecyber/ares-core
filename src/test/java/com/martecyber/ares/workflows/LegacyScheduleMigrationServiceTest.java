package com.martecyber.ares.workflows;

import com.martecyber.ares.agents.schedule.AgentTaskSchedule;
import com.martecyber.ares.agents.schedule.AgentTaskScheduleRepository;
import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.integrations.dto.IntegrationDto;
import com.martecyber.ares.integrations.schedule.IntegrationSchedule;
import com.martecyber.ares.integrations.schedule.IntegrationScheduleRepository;
import com.martecyber.ares.integrations.schedule.IntegrationScheduleService;
import com.martecyber.ares.kb.schedule.KbSyncSchedule;
import com.martecyber.ares.kb.schedule.KbSyncScheduleRepository;
import com.martecyber.ares.kb.schedule.KbSyncScheduleService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only the legacy-row selection/drain logic — each origin service's own graph-building is
 *  already covered by its own test suite (KbSyncScheduleServiceTest, etc.); this only checks
 *  that {@link LegacyScheduleMigrationService} calls the right create() with the right
 *  arguments and disables the row it read from, and skips rows that don't qualify. Agent task
 *  schedules and the ACTION_SYNC graph rewrite are the two exceptions where this class builds
 *  the migrated shape itself (no origin service to delegate to for the former; not a schedule at
 *  all for the latter) — those get their own more detailed assertions below. */
class LegacyScheduleMigrationServiceTest {

    private KbSyncScheduleRepository kbRepo;
    private KbSyncScheduleService kbService;
    private IntegrationScheduleRepository integrationRepo;
    private IntegrationScheduleService integrationService;
    private AgentTaskScheduleRepository agentTaskScheduleRepo;
    private WorkflowService workflowService;
    private WorkflowRepository workflowRepo;
    private IntegrationService dataSourceIntegrationService;
    private LegacyScheduleMigrationService service;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KbSyncScheduleRepository.class);
        kbService = mock(KbSyncScheduleService.class);
        integrationRepo = mock(IntegrationScheduleRepository.class);
        integrationService = mock(IntegrationScheduleService.class);
        agentTaskScheduleRepo = mock(AgentTaskScheduleRepository.class);
        workflowService = mock(WorkflowService.class);
        workflowRepo = mock(WorkflowRepository.class);
        dataSourceIntegrationService = mock(IntegrationService.class);
        service = new LegacyScheduleMigrationService(kbRepo, kbService, integrationRepo, integrationService,
            agentTaskScheduleRepo,
            workflowService, workflowRepo, dataSourceIntegrationService);

        when(kbRepo.findAll()).thenReturn(List.of());
        when(integrationRepo.findAll()).thenReturn(List.of());
        when(agentTaskScheduleRepo.findAll()).thenReturn(List.of());
        when(workflowRepo.findAll()).thenReturn(List.of());
    }

    @Test
    void migratesEachEnabledKbSyncScheduleAndDrainsItAfterward() {
        KbSyncSchedule enabled = new KbSyncSchedule();
        enabled.setSyncType("cve_update");
        enabled.setCronExpression("0 3 * * *");
        enabled.setEnabled(true);
        KbSyncSchedule alreadyDrained = new KbSyncSchedule();
        alreadyDrained.setSyncType("cwe");
        alreadyDrained.setEnabled(false);
        when(kbRepo.findAll()).thenReturn(List.of(enabled, alreadyDrained));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.kbSync());
        verify(kbService).create("cve_update", "0 3 * * *");
        verify(kbService, never()).create(eq("cwe"), any());
        assertFalse(enabled.isEnabled());
        verify(kbRepo).saveAll(List.of(enabled));
    }

    @Test
    void migratesEachEnabledIntegrationScheduleAndDrainsItAfterward() {
        IntegrationSchedule row = new IntegrationSchedule();
        row.setProjectId(5L);
        row.setIntegrationId(9L);
        row.setCapability("SYNC_ASSETS");
        row.setCronExpression("0 0 * * *");
        row.setEnabled(true);
        when(integrationRepo.findAll()).thenReturn(List.of(row));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.integrationSchedule());
        verify(integrationService).create(5L, 9L, "SYNC_ASSETS", "0 0 * * *");
        assertFalse(row.isEnabled());
    }

    @Test
    void emptyLegacyTablesMigrateNothingAndTouchNoRepository() {
        var result = service.migrateLegacySchedules();

        assertEquals(0, result.total());
        assertEquals(0, result.agentTaskWorkflowsUnlocked());
        assertEquals(0, result.integrationScheduleWorkflowsUnlocked());
        verifyNoInteractions(kbService, integrationService, workflowService);
        verify(kbRepo, never()).saveAll(any());
        verify(integrationRepo, never()).saveAll(any());
        verify(agentTaskScheduleRepo, never()).saveAll(any());
        verify(workflowRepo, never()).saveAll(any());
    }

    @Test
    void migratesEachEnabledAgentTaskScheduleIntoAnUnlockedActiveWorkflowAndDrainsItAfterward() {
        AgentTaskSchedule row = new AgentTaskSchedule();
        row.setName("Nightly nuclei");
        row.setProjectId(42L);
        row.setPoolId(7L);
        row.setTool("nuclei");
        row.setFormat("default");
        row.setArgs("{\"targets\":[\"1.2.3.4\"]}");
        row.setNacProfile("eu-west");
        row.setTimeoutMinutes(30);
        row.setCronExpression("0 2 * * *");
        row.setEnabled(true);
        AgentTaskSchedule alreadyDrained = new AgentTaskSchedule();
        alreadyDrained.setEnabled(false);
        when(agentTaskScheduleRepo.findAll()).thenReturn(List.of(row, alreadyDrained));
        Workflow created = mock(Workflow.class);
        when(created.getId()).thenReturn(99L);
        when(workflowService.create(eq(WorkflowScope.PROJECT), eq(42L), anyString(), anyString(), any(), isNull()))
            .thenReturn(new WorkflowSaveResult(created, List.of()));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.agentTaskSchedule());
        // Plain WorkflowService.create — never the locked/managed path — so the migrated workflow
        // stays editable from the Workflow editor (Tasks has no other page left to manage it from).
        verify(workflowService).create(eq(WorkflowScope.PROJECT), eq(42L),
            anyString(), anyString(), argThat(graph -> {
                @SuppressWarnings("unchecked")
                var nodes = (List<Map<String, Object>>) graph.get("nodes");
                assertEquals(2, nodes.size());
                var trigger = nodes.get(0);
                assertEquals("TRIGGER_CRON", trigger.get("type"));
                @SuppressWarnings("unchecked")
                var triggerConfig = (Map<String, Object>) ((Map<String, Object>) trigger.get("data")).get("config");
                assertEquals("0 2 * * *", triggerConfig.get("cronExpression"));
                var task = nodes.get(1);
                assertEquals("ACTION_AGENT_TASK", task.get("type"));
                @SuppressWarnings("unchecked")
                var taskConfig = (Map<String, Object>) ((Map<String, Object>) task.get("data")).get("config");
                assertEquals(7L, taskConfig.get("poolId"));
                assertEquals("nuclei", taskConfig.get("tool"));
                assertEquals("eu-west", taskConfig.get("nacProfile"));
                assertEquals(30, taskConfig.get("timeoutMinutes"));
                @SuppressWarnings("unchecked")
                var argsTemplate = (Map<String, Object>) taskConfig.get("argsTemplate");
                assertEquals(List.of("1.2.3.4"), argsTemplate.get("targets"));
                return true;
            }), isNull());
        // Flipped to active right after creation so it keeps firing on its original schedule.
        verify(workflowService).update(99L, null, null, "active", null);
        assertFalse(row.isEnabled());
        verify(agentTaskScheduleRepo).saveAll(List.of(row));
    }

    @Test
    void unlocksWorkflowsPreviouslyMigratedAsLockedAgentTaskSchedules() {
        Workflow stale = new Workflow();
        stale.setLocked(true);
        stale.setManagedBy("agent-task-schedule:5");
        Workflow other = new Workflow();
        other.setLocked(true);
        other.setManagedBy("kb-sync:cve_update"); // a legitimately-still-managed workflow
        Workflow alreadyPlain = new Workflow();
        alreadyPlain.setLocked(false); // never locked to begin with — untouched either way
        when(workflowRepo.findAll()).thenReturn(List.of(stale, other, alreadyPlain));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.agentTaskWorkflowsUnlocked());
        assertFalse(stale.isLocked());
        assertNull(stale.getManagedBy());
        assertTrue(other.isLocked());
        assertEquals("kb-sync:cve_update", other.getManagedBy());
        verify(workflowRepo).saveAll(List.of(stale));
    }

    @Test
    void unlocksWorkflowsPreviouslyManagedAsLockedIntegrationSchedules() {
        Workflow stale = new Workflow();
        stale.setLocked(true);
        stale.setManagedBy("integration-schedule:9:SYNC_ASSETS");
        Workflow other = new Workflow();
        other.setLocked(true);
        other.setManagedBy("kb-sync:cve_update"); // a legitimately-still-managed workflow
        Workflow alreadyPlain = new Workflow();
        alreadyPlain.setLocked(false); // never locked to begin with — untouched either way
        when(workflowRepo.findAll()).thenReturn(List.of(stale, other, alreadyPlain));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.integrationScheduleWorkflowsUnlocked());
        assertFalse(stale.isLocked());
        assertNull(stale.getManagedBy());
        assertTrue(other.isLocked());
        assertEquals("kb-sync:cve_update", other.getManagedBy());
        verify(workflowRepo).saveAll(List.of(stale));
    }

    @Test
    void rewritesActionSyncNodesIntoActionIntegrationCallUsingTheLiveIntegrationType() {
        Workflow wf = new Workflow();
        wf.setGraphDefinition("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_SYNC","data":{"label":"sync","config":{"integrationId":9,"capability":"SYNC_ASSETS"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"s1"}]}""");
        when(workflowRepo.findAll()).thenReturn(List.of(wf));
        when(dataSourceIntegrationService.get(9L)).thenReturn(new IntegrationDto(9L, null, "tenable-prod", "tenable",
            "organization", 1L, "active", "connected", null, null, null, OffsetDateTime.now(), OffsetDateTime.now()));

        var result = service.migrateLegacySchedules();

        assertEquals(1, result.syncNodesRewritten());
        verify(workflowRepo).save(wf);
        assertTrue(wf.getGraphDefinition().contains("\"type\":\"ACTION_INTEGRATION_CALL\""));
        assertTrue(wf.getGraphDefinition().contains("\"integrationType\":\"tenable\""));
        assertTrue(wf.getGraphDefinition().contains("\"action\":\"SYNC_ASSETS\""));
        assertFalse(wf.getGraphDefinition().contains("ACTION_SYNC"));
    }

    @Test
    void leavesActionSyncNodeUntouchedWhenItsIntegrationNoLongerResolves() {
        Workflow wf = new Workflow();
        String original = """
            {"nodes":[
              {"id":"s1","type":"ACTION_SYNC","data":{"label":"sync","config":{"integrationId":404,"capability":"SYNC_ASSETS"}}}
            ],"edges":[]}""";
        wf.setGraphDefinition(original);
        when(workflowRepo.findAll()).thenReturn(List.of(wf));
        when(dataSourceIntegrationService.get(404L)).thenThrow(new RuntimeException("not found"));

        var result = service.migrateLegacySchedules();

        assertEquals(0, result.syncNodesRewritten());
        verify(workflowRepo, never()).save(any());
        assertEquals(original, wf.getGraphDefinition());
    }
}
