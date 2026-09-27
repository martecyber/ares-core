package com.martecyber.ares.integrations.schedule;

import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowScope;
import com.martecyber.ares.workflows.WorkflowService;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Workflows Phase H+ (retiring ad hoc sync scheduling), now further retired: the "Integrations"
 * project page ({@code ProjectIntegrationController}/{@code ProjectIntegrationsView}) that used to
 * be this class's caller and the origin page a locked workflow pointed operators back to has been
 * removed outright — it had silently fallen out of the project nav and become unreachable, leaving
 * every schedule it created permanently un-editable (see {@code
 * LegacyScheduleMigrationService#unlockIntegrationScheduleWorkflows} for the one-time fix-up of
 * those). Going forward an operator builds/edits a {@code TRIGGER_CRON} + {@code
 * ACTION_INTEGRATION_CALL} workflow directly, same as any other schedule — so this class now only
 * exists to backfill the ancient {@code integration_schedule} table's leftover rows (see {@code
 * LegacyScheduleMigrationService#migrateIntegrationSchedules}) into a plain, unlocked, immediately
 * user-editable {@link Workflow} — no {@link com.martecyber.ares.workflows.ManagedWorkflowService}
 * involved, since there's no origin page left to lock it in favor of.
 */
@Service
public class IntegrationScheduleService {

    private final WorkflowService workflowService;
    private final IntegrationService integrationService;

    public IntegrationScheduleService(WorkflowService workflowService, IntegrationService integrationService) {
        this.workflowService = workflowService;
        this.integrationService = integrationService;
    }

    public void create(Long projectId, Long integrationId, String capability, String cronExpression) {
        validateCron(cronExpression);
        String type = integrationService.get(integrationId).type();
        Workflow wf = workflowService.create(WorkflowScope.PROJECT, projectId, capability + " schedule", null,
            buildGraph(type, integrationId, capability, cronExpression), null).workflow();
        workflowService.update(wf.getId(), null, null, "active", null);
    }

    private static Map<String, Object> buildGraph(String integrationType, Long integrationId, String capability, String cronExpression) {
        Map<String, Object> actionConfig = "ENRICH_ASSETS".equals(capability)
            ? Map.of("integrationType", integrationType, "integrationId", integrationId, "action", capability,
                     "paramsTemplate", Map.of("targetSource", "scope"))
            : Map.of("integrationType", integrationType, "integrationId", integrationId, "action", capability);
        return Map.of(
            "nodes", List.of(
                Map.of("id", "trigger", "type", "TRIGGER_CRON", "position", Map.of("x", 0, "y", 0),
                    "data", Map.of("label", "Schedule", "config", Map.of("cronExpression", cronExpression))),
                Map.of("id", "sync", "type", "ACTION_INTEGRATION_CALL", "position", Map.of("x", 0, "y", 150),
                    "data", Map.of("label", capability, "config", actionConfig))),
            "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "sync")));
    }

    /** Kept public static — {@code WorkflowCronPoller.computeNext} reuses this exact conversion
     *  rather than duplicating it (predates this class's own rewrite). */
    public static String toSpringCron(String fiveField) {
        String t = fiveField.trim();
        return t.split("\\s+").length == 6 ? t : "0 " + t;
    }

    private void validateCron(String cron) {
        try {
            CronExpression.parse(toSpringCron(cron));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + e.getMessage());
        }
    }
}
