package com.martecyber.ares.kb.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.workflows.ManagedWorkflowService;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowRunRepository;
import com.martecyber.ares.workflows.WorkflowScope;
import com.martecyber.ares.workflows.WorkflowService;
import com.martecyber.ares.workflows.WorkflowTrigger;
import com.martecyber.ares.workflows.WorkflowTriggerType;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Workflows Phase H+ (retiring ad hoc sync scheduling): "Schedule sync" for KB sync types now
 * creates/manages a locked, platform-scoped {@link Workflow} instead of a row in the legacy
 * {@code kb_sync_schedule} table — actually fired by {@code WorkflowCronPoller} +
 * {@code ACTION_INTEGRATION_CALL} through {@link KbSyncIntegrationActionHandler} (type
 * {@code "kb-sync"}), not by this class's own poller (the old {@code @Scheduled runDue()} is gone;
 * {@code kb_sync_schedule} itself is left in place but unused going forward, per the decision to
 * drain data rather than delete the old table/code in this pass).
 *
 * <p>{@code managedBy} is {@code "kb-sync:" + syncType} — a filter TAG, not a uniqueness key: the
 * UI allows several concurrent cron schedules for the same sync type (e.g. hourly AND daily), so
 * {@link ManagedWorkflowService#create} always inserts a new workflow rather than upserting.
 * {@link KbSyncScheduleDto#id} is the underlying {@code Workflow.id} — the controller's
 * {@code PATCH .../{id}/enabled} and {@code DELETE .../{id}} endpoints operate on it directly via
 * {@code ManagedWorkflowService}, unchanged from the frontend's point of view.
 */
@Service
public class KbSyncScheduleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ManagedWorkflowService managed;
    private final WorkflowService workflowService;
    private final WorkflowRunRepository runRepo;

    public KbSyncScheduleService(ManagedWorkflowService managed, WorkflowService workflowService, WorkflowRunRepository runRepo) {
        this.managed = managed;
        this.workflowService = workflowService;
        this.runRepo = runRepo;
    }

    public List<KbSyncScheduleDto> list(String syncType) {
        return managed.findAllTagged(managedByFor(syncType), WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID).stream()
            .map(this::toDto)
            .toList();
    }

    public KbSyncScheduleDto create(String syncType, String cronExpression) {
        validateCron(cronExpression);
        Map<String, Object> graph = Map.of(
            "nodes", List.of(
                Map.of("id", "trigger", "type", "TRIGGER_CRON", "position", Map.of("x", 0, "y", 0),
                    "data", Map.of("label", "Schedule", "config", Map.of("cronExpression", cronExpression))),
                Map.of("id", "sync", "type", "ACTION_INTEGRATION_CALL", "position", Map.of("x", 0, "y", 150),
                    "data", Map.of("label", labelFor(syncType), "config", Map.of(
                        "integrationType", "kb-sync", "integrationId", 0, "action", syncType)))),
            "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "sync")));

        Workflow wf = managed.create(managedByFor(syncType), WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            labelFor(syncType) + " schedule", null, graph);
        return toDto(wf);
    }

    public KbSyncScheduleDto setEnabled(Long workflowId, boolean enabled) {
        managed.setEnabled(workflowId, enabled);
        return toDto(workflowService.get(workflowId));
    }

    public void delete(Long workflowId) {
        managed.delete(workflowId);
    }

    private KbSyncScheduleDto toDto(Workflow wf) {
        WorkflowTrigger cronTrigger = workflowService.triggersFor(wf.getId()).stream()
            .filter(t -> WorkflowTriggerType.CRON.equals(t.getTriggerType()))
            .findFirst().orElse(null);
        String cronExpression = cronTrigger == null ? null : readCronExpression(cronTrigger.getConfig());
        boolean enabled = cronTrigger != null && cronTrigger.isEnabled();
        OffsetDateTime nextRunAt = cronTrigger == null ? null : cronTrigger.getNextRunAt();

        var lastRun = runRepo.findByWorkflowIdOrderByStartedAtDesc(wf.getId(), PageRequest.of(0, 1));
        OffsetDateTime lastRunAt = lastRun.isEmpty() ? null : lastRun.getContent().get(0).getStartedAt();

        String syncType = wf.getManagedBy().substring("kb-sync:".length());
        return new KbSyncScheduleDto(wf.getId(), syncType, cronExpression, enabled, lastRunAt, nextRunAt, wf.getCreatedAt());
    }

    private static String readCronExpression(String configJson) {
        try {
            var node = MAPPER.readTree(configJson).get("cronExpression");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String managedByFor(String syncType) {
        return "kb-sync:" + syncType;
    }

    private static String labelFor(String syncType) {
        return switch (syncType) {
            case "cve_update" -> "CVE sync";
            case "cwe" -> "CWE sync";
            case "capec" -> "CAPEC sync";
            case "attack" -> "ATT&CK sync";
            case "owasp" -> "OWASP sync";
            case "cisa_kev" -> "CISA KEV sync";
            case "vulncheck_kev" -> "VulnCheck KEV sync";
            case "exploitdb" -> "ExploitDB sync";
            case "vulncheck_xdb" -> "VulnCheck XDB sync";
            default -> throw new IllegalArgumentException("Unknown KB sync type: " + syncType);
        };
    }

    private static String toSpringCron(String fiveField) {
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
