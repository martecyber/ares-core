package com.martecyber.ares.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflows Phase H+ data migration (Phase 4 of the retirement plan): backfills a locked, managed
 * {@link Workflow} for every still-enabled row left over in the legacy scheduling tables from
 * before each origin service was swapped onto {@link ManagedWorkflowService} — those rows predate
 * the swap and were never touched afterwards, so nothing else creates their equivalent workflow.
 * Runs on every boot via {@code StartupCleanupService}, same pattern as {@code
 * MarkdownMigrationService}. Caido is deliberately excluded: its recurring mode was removed from
 * the UI outright rather than swapped onto a new backend mechanism (see {@code
 * CaidoIntegrationActionHandler}'s doc comment), so there's no new backing store for any legacy
 * recurring {@code caido_task}/{@code caido_api_task} row to migrate into.
 *
 * <p>KB sync and Integration schedule are drained (old row's {@code enabled} flag cleared) once
 * migrated, since {@code managedBy} there is an insert-only tag, not an upsert key — migrating the
 * same still-enabled row twice would create duplicate workflows. That also makes this naturally
 * idempotent across restarts: a migrated row no longer matches the {@code enabled = true} filter,
 * so it's simply skipped on the next boot. Agent Task Schedule follows the same drain pattern for
 * the same reason (Shodan's own equivalent migration was removed 2026-09-26 along with the rest of
 * the unreachable-from-the-UI {@code "shodan-task"} subsystem — see {@code ares-plugin-shodan} for
 * the real, reachable Shodan integration). Bug Hunting's own equivalent backfill moved out with the
 * rest of that subsystem — see {@code ares-plugin-bughunting}'s own {@code PluginLifecycle.onInstall}.
 *
 * <p>Also covers the Tasks-retirement follow-up: {@code agent_task_schedule} (the "Tasks" page's
 * own recurring-schedule table) was never swapped onto {@code ManagedWorkflowService} the way KB
 * sync/Integration schedule were, and its owning service is being deleted outright rather
 * than kept around as a thin wrapper (there's no successor "create a recurring agent task
 * schedule" UI — going forward an operator builds a {@code TRIGGER_CRON}+{@code
 * ACTION_AGENT_TASK} workflow directly), so this class builds that migrated graph inline instead
 * of delegating to an origin service's own {@code create()} the way the other four do. Unlike
 * those other four, the resulting workflow is deliberately created as a normal, unlocked,
 * user-editable {@link Workflow} via {@link WorkflowService#create} (then flipped to {@code
 * active}) rather than a locked one via {@link ManagedWorkflowService} — KB sync still has its own
 * dedicated "origin page" outside Workflows where an operator manages it (which
 * is exactly what a locked workflow's error message points them back to), but Tasks has none
 * anymore: once migrated, the Workflow editor is the ONLY place left to manage what used to be an
 * agent task schedule, so locking it out of that editor too would make it unmanageable entirely.
 * Integration schedule joined Tasks in this regard after its own origin page ({@code
 * ProjectIntegrationsView}) was retired for the same reason — see {@code IntegrationScheduleService}
 * and {@link #unlockIntegrationScheduleWorkflows} for that migration's own one-time unlock pass.
 * One capability is deliberately NOT preserved: {@code
 * batchSize}/{@code maxTargetsPerRun}/{@code coveredValues} (a "sample N not-yet-covered targets
 * per fire, rotate through the rest over several runs" feature) has no equivalent on {@code
 * ACTION_AGENT_TASK} today — a migrated schedule that used it fires against its FULL snapshotted
 * target list every time instead of a rotating subset. Logged loudly per-row so it's visible, not
 * silently dropped.
 *
 * <p>Also backfills {@code ACTION_SYNC} → {@code ACTION_INTEGRATION_CALL} inside every already-
 * saved {@link Workflow}'s own {@code graphDefinition} — seperate from the schedule migrations
 * above (it rewrites node configs in place rather than creating new workflows), needed because
 * {@code ACTION_SYNC} left {@code WorkflowNodeType.SUPPORTED_NODE_TYPES} when Sync was folded into
 * Integration Action (see {@code DataSourceSyncIntegrationActionHandler}) — any graph still
 * holding one would otherwise fail to load the moment {@code WorkflowGraphValidator} sees it
 * again. Deliberately scoped to the {@code workflow} table only, not {@code workflow_template}'s
 * own {@code graphDefinition} column — a template's resource ids are already stripped (see {@code
 * WorkflowTemplateGraphStripper}), so there's no real {@code integrationId} left in one to resolve
 * a concrete type from; no built-in template ever shipped with an {@code ACTION_SYNC} node
 * (verified — grep turns up nothing), so in practice this gap is only a concern for a
 * user-authored template, if any operator ever saved one.
 */
@Component
public class LegacyScheduleMigrationService {

    private static final Logger log = LoggerFactory.getLogger(LegacyScheduleMigrationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KbSyncScheduleRepository kbRepo;
    private final KbSyncScheduleService kbService;
    private final IntegrationScheduleRepository integrationRepo;
    private final IntegrationScheduleService integrationService;
    private final AgentTaskScheduleRepository agentTaskScheduleRepo;
    private final WorkflowService workflowService;
    private final WorkflowRepository workflowRepo;
    private final IntegrationService dataSourceIntegrationService;

    public LegacyScheduleMigrationService(KbSyncScheduleRepository kbRepo, KbSyncScheduleService kbService,
                                          IntegrationScheduleRepository integrationRepo, IntegrationScheduleService integrationService,
                                          AgentTaskScheduleRepository agentTaskScheduleRepo,
                                          WorkflowService workflowService,
                                          WorkflowRepository workflowRepo,
                                          IntegrationService dataSourceIntegrationService) {
        this.kbRepo = kbRepo;
        this.kbService = kbService;
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
        this.agentTaskScheduleRepo = agentTaskScheduleRepo;
        this.workflowService = workflowService;
        this.workflowRepo = workflowRepo;
        this.dataSourceIntegrationService = dataSourceIntegrationService;
    }

    /** Counts of legacy rows migrated in one run, per system. */
    public record MigrationResult(int kbSync, int integrationSchedule,
                                   int agentTaskSchedule, int syncNodesRewritten, int agentTaskWorkflowsUnlocked,
                                   int integrationScheduleWorkflowsUnlocked) {
        public int total() { return kbSync + integrationSchedule + agentTaskSchedule; }
    }

    @Transactional
    public MigrationResult migrateLegacySchedules() {
        return new MigrationResult(
            migrateKbSyncSchedules(),
            migrateIntegrationSchedules(),
            migrateAgentTaskSchedules(),
            rewriteSyncNodesToIntegrationCall(),
            unlockAgentTaskScheduleWorkflows(),
            unlockIntegrationScheduleWorkflows());
    }

    private int migrateKbSyncSchedules() {
        List<KbSyncSchedule> toMigrate = kbRepo.findAll().stream().filter(KbSyncSchedule::isEnabled).toList();
        if (toMigrate.isEmpty()) return 0;
        for (KbSyncSchedule row : toMigrate) {
            kbService.create(row.getSyncType(), row.getCronExpression());
            row.setEnabled(false);
        }
        kbRepo.saveAll(toMigrate);
        log.info("Migrated {} legacy KB sync schedule(s) into managed Workflows", toMigrate.size());
        return toMigrate.size();
    }

    private int migrateIntegrationSchedules() {
        List<IntegrationSchedule> toMigrate = integrationRepo.findAll().stream().filter(IntegrationSchedule::isEnabled).toList();
        if (toMigrate.isEmpty()) return 0;
        for (IntegrationSchedule row : toMigrate) {
            integrationService.create(row.getProjectId(), row.getIntegrationId(), row.getCapability(), row.getCronExpression());
            row.setEnabled(false);
        }
        integrationRepo.saveAll(toMigrate);
        log.info("Migrated {} legacy integration schedule(s) into managed Workflows", toMigrate.size());
        return toMigrate.size();
    }

    @SuppressWarnings("unchecked")
    private int migrateAgentTaskSchedules() {
        List<AgentTaskSchedule> toMigrate = agentTaskScheduleRepo.findAll().stream()
            .filter(AgentTaskSchedule::isEnabled).toList();
        if (toMigrate.isEmpty()) return 0;
        for (AgentTaskSchedule row : toMigrate) {
            if (row.getBatchSize() != null || row.getMaxTargetsPerRun() != null) {
                log.warn("Agent task schedule {} ('{}') used batchSize/maxTargetsPerRun target rotation — "
                    + "migrated to a workflow that fires against its full target list every run instead, "
                    + "no equivalent rotation exists on ACTION_AGENT_TASK yet. Review after migration.",
                    row.getId(), row.getName());
            }
            Map<String, Object> args;
            try {
                args = row.getArgs() == null ? Map.of() : MAPPER.readValue(row.getArgs(), Map.class);
            } catch (Exception e) {
                log.error("Agent task schedule {} has unparseable args JSON — skipping migration for this row", row.getId(), e);
                continue;
            }
            Map<String, Object> taskConfig = new LinkedHashMap<>();
            taskConfig.put("poolId", row.getPoolId());
            taskConfig.put("tool", row.getTool());
            taskConfig.put("format", row.getFormat());
            taskConfig.put("argsTemplate", args);
            if (row.getNacProfile() != null) taskConfig.put("nacProfile", row.getNacProfile());
            if (row.getTimeoutMinutes() != null) taskConfig.put("timeoutMinutes", row.getTimeoutMinutes());

            Map<String, Object> graph = Map.of(
                "nodes", List.of(
                    Map.of("id", "trigger", "type", "TRIGGER_CRON", "position", Map.of("x", 0, "y", 0),
                        "data", Map.of("label", "Schedule", "config", Map.of("cronExpression", row.getCronExpression()))),
                    Map.of("id", "task", "type", "ACTION_AGENT_TASK", "position", Map.of("x", 0, "y", 150),
                        "data", Map.of("label", row.getName() != null ? row.getName() : ("Agent task: " + row.getTool()), "config", taskConfig))),
                "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "task")));

            String name = (row.getName() != null && !row.getName().isBlank() ? row.getName() : "Agent task") + " (migrated schedule)";
            // Plain, unlocked WorkflowService.create — not ManagedWorkflowService — so the migrated
            // workflow stays fully editable from the Workflow editor (see class javadoc: Tasks has
            // no "origin page" left to point a locked-workflow error message back to). create()
            // always starts a workflow in draft; flip it to active immediately after so the
            // migrated schedule keeps firing on the same cron it always did, uninterrupted by the
            // migration.
            Workflow wf = workflowService.create(WorkflowScope.PROJECT, row.getProjectId(), name,
                "Migrated from the retired Tasks feature's recurring schedule.", graph, null).workflow();
            workflowService.update(wf.getId(), null, null, "active", null);
            row.setEnabled(false);
        }
        agentTaskScheduleRepo.saveAll(toMigrate);
        log.info("Migrated {} legacy agent task schedule(s) into managed Workflows", toMigrate.size());
        return toMigrate.size();
    }

    /** One-time fix-up for workflows {@link #migrateAgentTaskSchedules} created back when it still
     *  went through {@code ManagedWorkflowService} (locked, {@code managedBy="agent-task-schedule:"
     *  + id}) before that was corrected to the plain, unlocked {@link WorkflowService#create} path
     *  above. Those already-created rows were never touched by the fix — the source {@code
     *  AgentTaskSchedule} row was already drained, so {@link #migrateAgentTaskSchedules} simply had
     *  nothing left to (re-)migrate on the next boot — leaving them permanently locked and
     *  unmanageable (no origin page left to point their "manage it from its origin page instead"
     *  error at, per that method's own javadoc). Idempotent: once unlocked, {@code managedBy} is
     *  cleared too, so a later run's {@code managedBy != null} filter no longer matches them. */
    private int unlockAgentTaskScheduleWorkflows() {
        List<Workflow> toUnlock = workflowRepo.findAll().stream()
            .filter(wf -> wf.isLocked() && wf.getManagedBy() != null && wf.getManagedBy().startsWith("agent-task-schedule:"))
            .toList();
        if (toUnlock.isEmpty()) return 0;
        for (Workflow wf : toUnlock) {
            wf.setLocked(false);
            wf.setManagedBy(null);
        }
        workflowRepo.saveAll(toUnlock);
        log.info("Unlocked {} previously-migrated agent-task-schedule workflow(s) so they're editable/deletable again", toUnlock.size());
        return toUnlock.size();
    }

    /** One-time fix-up mirroring {@link #unlockAgentTaskScheduleWorkflows} for the "Integrations"
     *  project page's retirement: {@code IntegrationScheduleService} used to lock every schedule it
     *  created via {@code ManagedWorkflowService}, pointing operators back to {@code
     *  ProjectIntegrationsView} to manage it — but that page had already fallen out of the project
     *  nav (unreachable) before being removed outright, so those locked workflows were stuck
     *  permanently un-editable with no reachable origin page left to manage them from. Idempotent:
     *  once unlocked, {@code managedBy} is cleared too, so a later run's filter no longer matches. */
    private int unlockIntegrationScheduleWorkflows() {
        List<Workflow> toUnlock = workflowRepo.findAll().stream()
            .filter(wf -> wf.isLocked() && wf.getManagedBy() != null && wf.getManagedBy().startsWith("integration-schedule:"))
            .toList();
        if (toUnlock.isEmpty()) return 0;
        for (Workflow wf : toUnlock) {
            wf.setLocked(false);
            wf.setManagedBy(null);
        }
        workflowRepo.saveAll(toUnlock);
        log.info("Unlocked {} previously-managed integration-schedule workflow(s) so they're editable/deletable again", toUnlock.size());
        return toUnlock.size();
    }

    /** Rewrites every {@code ACTION_SYNC} node still present in a saved {@link Workflow}'s graph
     *  into the equivalent {@code ACTION_INTEGRATION_CALL} shape — {@code capability} values are
     *  reused verbatim as {@code action} codes (see {@code DataSourceSyncIntegrationActionHandler}
     *  and its Config class, which register the exact same code strings ACTION_SYNC used), only
     *  {@code integrationType} needs resolving, via a live lookup of the node's own {@code
     *  integrationId} (unlike ACTION_SYNC, which resolved the type at execute time; a saved
     *  ACTION_INTEGRATION_CALL node needs it up front in its config). A node whose
     *  {@code integrationId} no longer resolves (deleted integration) is left as ACTION_SYNC and
     *  logged — better a workflow the operator notices is stuck failing validation and fixes by
     *  hand than a silently-wrong rewrite. */
    private int rewriteSyncNodesToIntegrationCall() {
        int rewritten = 0;
        for (Workflow wf : workflowRepo.findAll()) {
            String json = wf.getGraphDefinition();
            if (json == null || !json.contains(WorkflowNodeType.ACTION_SYNC)) continue;
            try {
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(json);
                JsonNode nodes = root.get("nodes");
                if (nodes == null || !nodes.isArray()) continue;
                boolean changed = false;
                for (JsonNode node : nodes) {
                    if (!WorkflowNodeType.ACTION_SYNC.equals(node.path("type").asText(null))) continue;
                    JsonNode config = node.path("data").path("config");
                    long integrationId = config.path("integrationId").asLong(-1);
                    String capability = config.path("capability").asText(null);
                    IntegrationDto integration = null;
                    if (integrationId >= 0) {
                        try { integration = dataSourceIntegrationService.get(integrationId); }
                        catch (Exception ignored) { /* handled below via null check */ }
                    }
                    if (integration == null || capability == null) {
                        log.warn("Workflow {} node '{}': ACTION_SYNC references integration {} which no longer "
                            + "resolves — left as-is, this node will fail validation until fixed by hand",
                            wf.getId(), node.path("id").asText(), integrationId);
                        continue;
                    }
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("type", "ACTION_INTEGRATION_CALL");
                    var newConfig = MAPPER.createObjectNode();
                    newConfig.put("integrationType", integration.type());
                    newConfig.put("integrationId", integrationId);
                    newConfig.put("action", capability);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("data")).set("config", newConfig);
                    changed = true;
                    rewritten++;
                }
                if (changed) {
                    wf.setGraphDefinition(MAPPER.writeValueAsString(root));
                    workflowRepo.save(wf);
                }
            } catch (Exception e) {
                log.error("Failed to rewrite ACTION_SYNC nodes for workflow {} — left unchanged", wf.getId(), e);
            }
        }
        if (rewritten > 0) log.info("Rewrote {} ACTION_SYNC node(s) into ACTION_INTEGRATION_CALL across saved workflows", rewritten);
        return rewritten;
    }
}
