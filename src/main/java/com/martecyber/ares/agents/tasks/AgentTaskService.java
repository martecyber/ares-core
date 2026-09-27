package com.martecyber.ares.agents.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.Agent;
import com.martecyber.ares.agents.AgentRepository;
import com.martecyber.ares.agents.schedule.AgentScheduleRunService;
import com.martecyber.ares.agents.pools.AgentPool;
import com.martecyber.ares.agents.pools.AgentPoolRepository;
import com.martecyber.ares.agents.pools.AgentPoolService;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.*;
import com.martecyber.ares.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.rules.EngagementRuleEnforcer;
import com.martecyber.ares.imports.ImportResult;
import com.martecyber.ares.imports.ImportService;
import com.martecyber.ares.projects.ProjectRepository;
import jakarta.transaction.Transactional;
import java.time.Duration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTaskRepository taskRepo;
    private final AgentPoolService poolService;
    private final AgentPoolRepository poolRepo;
    private final AgentToolSpecRegistry agentToolSpecRegistry;
    private final ImportService importService;
    private final ProjectRepository projectRepo;
    private final TargetResolver resolver;
    private final AgentRepository agentRepo;
    private final AgentScheduleRunService scheduleRuns;
    private final EngagementRuleEnforcer ruleEnforcer;

    public AgentTaskService(AgentTaskRepository taskRepo,
                            AgentPoolService poolService,
                            AgentPoolRepository poolRepo,
                            AgentToolSpecRegistry agentToolSpecRegistry,
                            ImportService importService,
                            ProjectRepository projectRepo,
                            TargetResolver resolver,
                            AgentRepository agentRepo,
                            AgentScheduleRunService scheduleRuns,
                            EngagementRuleEnforcer ruleEnforcer) {
        this.taskRepo = taskRepo;
        this.poolService = poolService;
        this.poolRepo = poolRepo;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
        this.importService = importService;
        this.projectRepo = projectRepo;
        this.resolver = resolver;
        this.agentRepo = agentRepo;
        this.scheduleRuns = scheduleRuns;
        this.ruleEnforcer = ruleEnforcer;
    }

    // ── Project side: list, create, cancel ────────────────────────────────

    public Page<TaskDto> listForProject(Long projectId, int page, int size) {
        return mapWithAgentNames(taskRepo.findByProjectIdOrderByCreatedAtDesc(projectId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200))));
    }

    /** Recent tasks executed (or queued) by a specific agent — for the agent detail view. */
    public Page<TaskDto> listForAgent(Long agentId, int page, int size) {
        return mapWithAgentNames(taskRepo.findByAgentIdOrderByCreatedAtDesc(agentId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200))));
    }

    private static final Set<String> AT_SORT_FIELDS = Set.of("createdAt", "startedAt", "completedAt", "tool", "status", "actualDurationMs", "name");

    /**
     * Global cross-project listing — powers both the platform-wide operations view and (filtered
     * to one {@code poolId} and {@code status=pending}) the paginated queue table under an agent
     * pool's load graph. Resolves project, pool, and agent display names in bulk.
     */
    public Page<GlobalTaskDto> listAll(List<String> statuses, List<String> tools, Long poolId,
                                       String sortBy, String sortDir, int page, int size) {
        String col = AT_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        List<String> statusList = (statuses == null || statuses.isEmpty()) ? null : statuses;
        List<String> toolList   = (tools    == null || tools.isEmpty())    ? null : tools;
        Specification<AgentTask> spec = Specification.<AgentTask>where(
            statusList == null ? null : (root, q, cb) -> root.get("status").in(statusList)
        ).and(
            toolList   == null ? null : (root, q, cb) -> root.get("tool").in(toolList)
        ).and(
            poolId     == null ? null : (root, q, cb) -> cb.equal(root.get("poolId"), poolId)
        );
        Page<AgentTask> raw = taskRepo.findAll(spec,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(dir, col)));

        Set<Long> agentIds = new HashSet<>(), poolIds = new HashSet<>(), projectIds = new HashSet<>();
        for (AgentTask t : raw.getContent()) {
            if (t.getAgentId() != null) agentIds.add(t.getAgentId());
            poolIds.add(t.getPoolId());
            projectIds.add(t.getProjectId());
        }
        Map<Long, String> agentNames = new HashMap<>();
        if (!agentIds.isEmpty())
            agentRepo.findAllById(agentIds).forEach(a -> agentNames.put(a.getId(), a.getName()));

        Map<Long, String> poolNames = new HashMap<>();
        if (!poolIds.isEmpty())
            poolRepo.findAllById(poolIds).forEach((AgentPool p) -> poolNames.put(p.getId(), p.getName()));

        Map<Long, String> projectNames = new HashMap<>();
        Map<Long, String> projectCodes = new HashMap<>();
        Map<Long, Long>   projectOrgIds = new HashMap<>();
        if (!projectIds.isEmpty())
            projectRepo.findAllById(projectIds).forEach((Project p) -> {
                projectNames.put(p.getId(), p.getName());
                projectCodes.put(p.getId(), p.getCode());
                projectOrgIds.put(p.getId(), p.getOrganizationId());
            });

        return raw.map(t -> GlobalTaskDto.from(
            t,
            agentNames.get(t.getAgentId()),
            poolNames.get(t.getPoolId()),
            projectNames.get(t.getProjectId()),
            projectCodes.get(t.getProjectId()),
            projectOrgIds.get(t.getProjectId())
        ));
    }

    /** Single-task detail for the read-only preview modal — same field set {@link #listAll}
     *  returns, resolved for just the one row (agent pool timeline queue table + timeline bars
     *  both open this by id rather than carrying the full record around client-side). */
    public GlobalTaskDto getGlobal(Long id) {
        AgentTask t = taskRepo.findById(id).orElseThrow(() -> NotFoundException.of("agent task", id));
        String agentName = t.getAgentId() == null ? null : agentRepo.findById(t.getAgentId()).map(Agent::getName).orElse(null);
        String poolName = poolRepo.findById(t.getPoolId()).map(AgentPool::getName).orElse(null);
        Project project = projectRepo.findById(t.getProjectId()).orElse(null);
        return GlobalTaskDto.from(t, agentName, poolName,
            project == null ? null : project.getName(),
            project == null ? null : project.getCode(),
            project == null ? null : project.getOrganizationId());
    }

    /** Shared mapper: bulk-resolves agent display names for any AgentTask page. */
    private Page<TaskDto> mapWithAgentNames(Page<AgentTask> rows) {
        Set<Long> ids = new HashSet<>();
        for (AgentTask t : rows.getContent()) if (t.getAgentId() != null) ids.add(t.getAgentId());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Agent a : agentRepo.findAllById(ids)) names.put(a.getId(), a.getName());
        }
        return rows.map(t -> TaskDto.from(t, t.getAgentId() == null ? null : names.get(t.getAgentId())));
    }

    /** Resolve a single agent's display name; null when unset or missing. */
    private String agentNameFor(Long agentId) {
        if (agentId == null) return null;
        return agentRepo.findById(agentId).map(Agent::getName).orElse(null);
    }

    /**
     * Creates one or more tasks from a single request. When {@code req.batchSize()} is set
     * and the resolved target list is larger, the list is split into batches and one task
     * is created per batch. The returned list always has at least one element.
     *
     * <p>{@code REQUIRES_NEW}, not the default {@code REQUIRED} — {@code WorkflowRunService.
     * executeAgentTask()} calls this from within {@code executeNode()}'s own already-open
     * transaction, and this method can legitimately throw mid-way (bad pool grant, a selector
     * over the target-count limit, an invalid tool arg, ...). Left as {@code REQUIRED}, that
     * exception marks the CALLER's shared transaction rollback-only before {@code executeNode()}'s
     * own try/catch ever gets a chance to record the step as FAILED — the catch block's writes
     * then look like they succeeded, but silently no-op, and the real failure surfaces instead as
     * an opaque {@code UnexpectedRollbackException} at commit time. {@code REQUIRES_NEW} isolates
     * this method's own transaction so a failure here rolls back only its own work, leaving the
     * caller's transaction free to record a clean FAILED step — same fix, same reasoning, already
     * applied to {@code IntegrationActionRegistry#start} for the equivalent ACTION_INTEGRATION_CALL
     * case. Safe for the other caller too ({@code AgentTaskController.createAll}): a plain REST
     * request has no surrounding transaction to isolate from, so REQUIRES_NEW is a no-op there.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<TaskDto> createAll(Long projectId, CreateTask req, Long createdBy) {
        if (req == null || req.poolId() == null || req.tool() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "poolId and tool are required");
        }

        Long orgId = projectRepo.findById(projectId)
            .map(p -> p.getOrganizationId())
            .orElseThrow(() -> NotFoundException.of("project", projectId));
        if (!poolService.isGrantedToProject(req.poolId(), orgId, projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Project does not have access to this agent pool");
        }

        // Resolve selector → concrete targets (snapshot). One-shot tasks are immutable
        // once created; recurring schedules re-resolve at every fire. MAX_TARGETS is only
        // enforced when batchSize is unset — a caller that configured batching has already
        // opted into subdividing an arbitrarily large result set into several tasks (see the
        // partition() call below), which is exactly what TargetResolver#resolveInto's unlimited
        // overload exists for; enforcing the cap unconditionally here defeated batchSize
        // entirely, since it made a selector over 5,000 targets fail outright before batching
        // ever got a chance to split it.
        Integer batchSize = req.batchSize();
        boolean isBatched = batchSize != null && batchSize > 0;
        Map<String, Object> resolvedArgs = resolver.resolveInto(projectId,
            req.args() == null ? Map.of() : req.args(), !isBatched);

        // Enforce project rules: blocks if outside time window, injects headers + rate limit.
        ruleEnforcer.applyToTaskArgs(projectId, req.tool(), resolvedArgs, req.bypassTimeWindow());

        agentToolSpecRegistry.validateArgs(req.tool(), resolvedArgs);

        // maxTargets: 1 (wpscan, ffuf) enforcement for THIS path — unlike a workflow node (which
        // WorkflowGraphValidator already blocks at save time via batchSize), a manually-created or
        // schedule-fired task never goes through that check, so this is the only place guarding
        // against handing one of these tools more targets than it can run in a single invocation.
        Integer maxTargets = agentToolSpecRegistry.find(req.tool()).map(AgentToolSpec::maxTargets).orElse(null);
        if (maxTargets != null) {
            if (isBatched && batchSize > maxTargets) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tool '" + req.tool() + "' accepts at most " + maxTargets + " target(s) per task — batchSize must be <= " + maxTargets);
            }
            if (!isBatched) {
                @SuppressWarnings("unchecked")
                List<String> unbatchedTargets = (List<String>) resolvedArgs.get("targets");
                if (unbatchedTargets != null && unbatchedTargets.size() > maxTargets) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + req.tool() + "' accepts at most " + maxTargets + " target(s) per task — set batchSize to " + maxTargets);
                }
            }
        }

        if (isBatched) {
            @SuppressWarnings("unchecked")
            List<String> targets = (List<String>) resolvedArgs.get("targets");
            if (targets != null && targets.size() > batchSize) {
                List<List<String>> batches = partition(targets, batchSize);
                List<TaskDto> result = new ArrayList<>(batches.size());
                for (int i = 0; i < batches.size(); i++) {
                    Map<String, Object> batchArgs = new LinkedHashMap<>(resolvedArgs);
                    batchArgs.put("targets", batches.get(i));
                    String batchName = req.name() != null && !req.name().isBlank()
                        ? req.name() + " (" + (i + 1) + "/" + batches.size() + ")"
                        : null;
                    result.add(buildAndSave(projectId, req, batchArgs, batchName, createdBy));
                }
                return result;
            }
        }
        return List.of(buildAndSave(projectId, req, resolvedArgs, req.name(), createdBy));
    }

    private TaskDto buildAndSave(Long projectId, CreateTask req, Map<String, Object> resolvedArgs,
                                  String name, Long createdBy) {
        AgentTask t = new AgentTask();
        t.setName(blank(name));
        t.setProjectId(projectId);
        t.setPoolId(req.poolId());
        t.setTool(req.tool());
        t.setFormat(req.format() == null || req.format().isBlank() ? "default" : req.format());
        try { t.setArgs(MAPPER.writeValueAsString(resolvedArgs)); }
        catch (Exception e) { t.setArgs("{}"); }
        t.setNacProfile(blank(req.nacProfile()));
        t.setStatus("pending");
        t.setPriority(req.priority() != null ? req.priority() : 0);
        t.setTimeoutMinutes(req.timeoutMinutes());
        t.setCreatedBy(createdBy);
        t.setCreatedAt(OffsetDateTime.now());
        // Future timestamp ⇒ delayed one-shot; past/now or null ⇒ run ASAP.
        if (req.scheduledFor() != null && req.scheduledFor().isAfter(OffsetDateTime.now())) {
            t.setScheduledFor(req.scheduledFor());
        }
        return TaskDto.from(taskRepo.save(t));
    }

    /** Computes actual_duration_ms from started_at→completed_at when both are present. */
    private static Long computeDuration(AgentTask t) {
        if (t.getStartedAt() == null || t.getCompletedAt() == null) return null;
        return Duration.between(t.getStartedAt(), t.getCompletedAt()).toMillis();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return parts;
    }

    @Transactional
    public TaskDto cancel(Long projectId, Long taskId) {
        AgentTask t = load(taskId);
        if (!t.getProjectId().equals(projectId)) throw NotFoundException.of("agent task", taskId);
        if ("completed".equals(t.getStatus()) || "failed".equals(t.getStatus()) || "cancelled".equals(t.getStatus())) {
            return TaskDto.from(t, agentNameFor(t.getAgentId()));
        }
        t.setStatus("cancelled");
        t.setCompletedAt(OffsetDateTime.now());
        t.setActualDurationMs(computeDuration(t));
        AgentTask saved = taskRepo.save(t);
        scheduleRuns.onTaskTerminal(saved);
        return TaskDto.from(saved, agentNameFor(saved.getAgentId()));
    }

    // ── Agent side: claim, mark started, complete, fail ──────────────────

    /**
     * Atomically picks the next pending task for this agent's pools. Empty if none.
     * Implemented as SELECT … FOR UPDATE SKIP LOCKED followed by an UPDATE inside the
     * same transaction so the row lock acquired in step 1 protects the row through step 2,
     * blocking any racing agent from claiming it.
     */
    @Transactional
    public Optional<TaskAssignment> claimNext(Long agentId) {
        List<String> tools = availableToolsFor(agentId);
        if (tools.isEmpty()) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.now();
        Long id = taskRepo.pickNextPendingForAgent(agentId, now, tools);
        if (id == null) return Optional.empty();
        AgentTask t = taskRepo.findById(id).orElse(null);
        if (t == null) return Optional.empty();
        t.setAgentId(agentId);
        t.setStatus("dispatched");
        t.setDispatchedAt(now);
        taskRepo.save(t);
        return Optional.of(TaskAssignment.from(t));
    }

    /**
     * Atomically claims up to {@code count} pending tasks for this agent in one DB round-trip.
     * Uses the same SKIP LOCKED pattern as {@link #claimNext} — safe under concurrent agents.
     */
    @Transactional
    public List<TaskAssignment> claimNextBatch(Long agentId, int count) {
        if (count <= 0) return List.of();
        List<String> tools = availableToolsFor(agentId);
        if (tools.isEmpty()) return List.of();
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> ids = taskRepo.pickNextNPendingForAgent(agentId, now, tools, count);
        if (ids.isEmpty()) return List.of();
        List<TaskAssignment> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            AgentTask t = taskRepo.findById(id).orElse(null);
            if (t == null) continue;
            t.setAgentId(agentId);
            t.setStatus("dispatched");
            t.setDispatchedAt(now);
            taskRepo.save(t);
            result.add(TaskAssignment.from(t));
        }
        return result;
    }

    public boolean hasPendingForAgent(Long agentId) {
        List<String> tools = availableToolsFor(agentId);
        if (tools.isEmpty()) return false;
        return taskRepo.hasPendingForAgent(agentId, OffsetDateTime.now(), tools);
    }

    /**
     * Extracts the tool names the agent reported as locally available (last seen via
     * {@code /heartbeat}). The {@code capabilities} JSONB column is shaped as
     * {@code [{"tool":"<toolId>","path":"...","version":"...","canRoot":true}, ...]} —
     * we project it down to just the tool ids the dispatcher needs to filter on.
     *
     * Returns an empty list when the agent doesn't exist, has never enrolled, or
     * has no detected tools — in any of those cases there's nothing to claim.
     */
    private List<String> availableToolsFor(Long agentId) {
        Agent a = agentRepo.findById(agentId).orElse(null);
        if (a == null) return List.of();
        String caps = a.getCapabilities();
        if (caps == null || caps.isBlank()) return List.of();
        try {
            List<Map<String, Object>> entries = MAPPER.readValue(caps, new TypeReference<>() {});
            List<String> out = new ArrayList<>(entries.size());
            for (Map<String, Object> e : entries) {
                Object tool = e.get("tool");
                if (tool != null) out.add(tool.toString());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Returns tasks still in dispatched/running state for this agent, so it can re-execute them after a restart. */
    public List<TaskAssignment> assignedToAgent(Long agentId) {
        return taskRepo.findByAgentIdAndStatusIn(agentId, List.of("dispatched", "running"))
            .stream()
            .map(TaskAssignment::from)
            .toList();
    }

    @Transactional
    public void markStarted(Long agentId, Long taskId) {
        AgentTask t = loadForAgent(agentId, taskId);
        if (!"dispatched".equals(t.getStatus())) return;
        t.setStatus("running");
        t.setStartedAt(OffsetDateTime.now());
        taskRepo.save(t);
    }

    /**
     * Agent uploads tool output. Bytes are piped through {@link ImportService#startImport} —
     * NOT {@code runImport} — exactly as if a human had uploaded the file via {@code /imports},
     * including its own non-blocking behavior: the actual parsing/persistence can take minutes
     * for a large result set (see that method's own doc), so this returns as soon as the upload
     * itself is safely queued rather than blocking the agent's own HTTP client until it's fully
     * processed. The task's real {@code completed}/{@code failed} transition happens later, in
     * {@link #finishAfterImport}, once that background work is done — this method only gets it
     * as far as {@code "uploading"}, same as it always did while the import ran.
     *
     * The agent supplies its runtime-determined sourceIp (private interface IP for
     * private targets, public NAT'd IP for Internet targets). If provided, it
     * overrides any stored value and is recorded on the task for audit.
     */
    @Transactional
    public TaskDto complete(Long agentId, Long taskId, byte[] resultBytes,
                            Integer exitCode, String stderr, String agentSourceIp) {
        AgentTask t = loadForAgent(agentId, taskId);
        t.setExitCode(exitCode);
        t.setStderr(truncate(stderr, 8000));
        t.setStatus("uploading");
        String effectiveSourceIp = (agentSourceIp != null && !agentSourceIp.isBlank())
            ? agentSourceIp.trim() : t.getSourceIp();
        if (agentSourceIp != null && !agentSourceIp.isBlank()) {
            t.setSourceIp(effectiveSourceIp);  // store agent-reported value for audit
        }
        taskRepo.save(t);

        boolean scopeFilterDisabled = false;
        if (t.getArgs() != null) {
            try {
                Map<String, Object> taskArgs = MAPPER.readValue(t.getArgs(), new TypeReference<>() {});
                scopeFilterDisabled = Boolean.TRUE.equals(taskArgs.get("scopeFilterDisabled"));
            } catch (Exception ignored) {}
        }
        try {
            // Extension is cosmetic only — ImportParser dispatch is keyed by `format` (the
            // parser id), never by filename, so a per-tool extension map has no functional
            // purpose and would just be one more place ares-core would need to know a real
            // plugin's identity (see AgentToolSpec#outputArgs's own doc for the same call on
            // the agent side, which standardized on a generic extension for the same reason).
            importService.startImport(
                t.getProjectId(), t.getTool(), t.getFormat(),
                "agent-task-" + t.getId() + ".out",
                resultBytes, effectiveSourceIp, t.getNacProfile(), scopeFilterDisabled,
                ir -> finishAfterImport(taskId, exitCode, resultBytes, stderr, ir, null)
            );
        } catch (Exception e) {
            // Only a genuinely bad tool/plugin id lands here — startImport validates
            // synchronously before queuing anything, so nothing was actually dispatched.
            finishAfterImport(taskId, exitCode, resultBytes, stderr, null, e);
        }
        return TaskDto.from(t, agentNameFor(t.getAgentId()));
    }

    /**
     * Runs once the background import {@link #complete} kicked off actually finishes (or never
     * even started — see {@code startupError}) — on the import's own background thread, never
     * the original request's, so this re-loads the task fresh rather than reusing the (by-then-
     * detached) entity {@link #complete} had. Exactly the same completed-vs-failed decision
     * {@link #complete} used to apply inline before this was made non-blocking.
     */
    @Transactional
    void finishAfterImport(Long taskId, Integer exitCode, byte[] resultBytes, String stderr,
                            ImportResult ir, Exception startupError) {
        AgentTask t = taskRepo.findById(taskId).orElse(null);
        if (t == null) return; // task row is gone — nothing to update
        if (startupError != null) {
            t.setStatus("failed");
            t.setError(truncate(startupError.getMessage(), 8000));
        } else if (ir.isSuccess()) {
            // If the tool exited non-zero and produced no output, the import
            // "succeeded" with 0 findings only because the file was empty.
            // Treat this as a tool failure and surface the stderr content so
            // operators see the real error instead of a silent zero-result run.
            boolean toolFailed = exitCode != null && exitCode != 0;
            boolean noOutput   = resultBytes == null || resultBytes.length == 0;
            if (toolFailed && noOutput && stderr != null && !stderr.isBlank()) {
                t.setStatus("failed");
                t.setError(truncate(stderr.strip(), 2000));
            } else {
                t.setStatus("completed");
            }
        } else {
            t.setStatus("failed");
            t.setError(ir.getMessage());
        }
        t.setCompletedAt(OffsetDateTime.now());
        t.setActualDurationMs(computeDuration(t));
        AgentTask saved = taskRepo.save(t);
        scheduleRuns.onTaskTerminal(saved);
    }

    @Transactional
    public TaskDto fail(Long agentId, Long taskId, String error) {
        AgentTask t = taskRepo.findById(taskId)
            .orElseThrow(() -> NotFoundException.of("agent_task", taskId));
        if (Set.of("failed", "completed", "cancelled").contains(t.getStatus())) {
            return TaskDto.from(t, agentNameFor(t.getAgentId()));
        }
        t.setStatus("failed");
        t.setError(truncate(error, 8000));
        t.setCompletedAt(OffsetDateTime.now());
        t.setActualDurationMs(computeDuration(t));
        AgentTask saved = taskRepo.save(t);
        try { scheduleRuns.onTaskTerminal(saved); }
        catch (Exception e) { log.warn("scheduleRuns hook failed for task {}: {}", taskId, e.getMessage()); }
        return TaskDto.from(saved, agentNameFor(saved.getAgentId()));
    }

    /**
     * Returns true when this agent's task has been cancelled server-side (e.g. by a
     * project user while the tool was already running). Used by the agent to decide
     * whether to kill the in-flight subprocess.
     * Unknown/unowned tasks return false so the agent keeps running rather than
     * silently aborting work it doesn't own.
     */
    public boolean isCancelled(Long agentId, Long taskId) {
        return taskRepo.findByIdAndAgentId(taskId, agentId)
            .map(t -> "cancelled".equals(t.getStatus()))
            .orElse(false);
    }

    // ── Queue recovery ────────────────────────────────────────────────────

    /**
     * Called on every heartbeat. Compares tasks this agent reports as active against those
     * the DB believes are dispatched/running for it. Any task not in {@code activeTaskIds}
     * has been silently dropped (agent crash, restart) and is reset to {@code pending} so
     * another eligible agent can pick it up.
     *
     * Skips reconciliation when {@code activeTaskIds} is {@code null} to preserve
     * compatibility with older agent versions that don't send this field.
     */
    @Transactional
    public void reconcileAgentTasks(Long agentId, List<Long> activeTaskIds) {
        if (activeTaskIds == null) return;
        int n = activeTaskIds.isEmpty()
            ? taskRepo.resetAllDroppedByAgent(agentId, OffsetDateTime.now().minusMinutes(2))
            : taskRepo.resetDroppedByAgent(agentId, activeTaskIds);
        if (n > 0) log.warn("Task recovery: reset {} task(s) dropped by agent {} → pending", n, agentId);
    }

    /**
     * Scheduled safety net: resets dispatched/running tasks whose agent has gone silent
     * (no heartbeat for {@code thresholdMinutes}) back to {@code pending}.
     * Returns the count of rows reset.
     */
    @Transactional
    public int resetOrphanedTasks(int thresholdMinutes) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(thresholdMinutes);
        int n = taskRepo.resetOrphanedByOfflineAgents(cutoff);
        if (n > 0) log.warn("Orphan recovery: reset {} task(s) to pending (agent offline >{}m)", n, thresholdMinutes);
        return n;
    }

    /**
     * Scheduled safety net: cancels tasks still dispatched/running/uploading past their
     * own configured timeout_minutes. Reuses the same {@code status = 'cancelled'} signal
     * the manual {@link #cancel} path uses — the agent's next poll sees it via
     * {@link #isCancelled} and kills the in-flight subprocess, same as a user-initiated cancel.
     * Returns the count of tasks cancelled.
     */
    @Transactional
    public int cancelTimedOutTasks() {
        int n = 0;
        for (Long id : taskRepo.findTimedOutTaskIds()) {
            AgentTask t = taskRepo.findById(id).orElse(null);
            if (t == null) continue;
            // Re-check status: it may have completed/failed/been cancelled between the
            // bulk id lookup above and this per-row load.
            if (Set.of("completed", "failed", "cancelled").contains(t.getStatus())) continue;
            t.setStatus("cancelled");
            t.setError("Cancelled: exceeded configured timeout of " + t.getTimeoutMinutes() + " minute(s)");
            t.setCompletedAt(OffsetDateTime.now());
            t.setActualDurationMs(computeDuration(t));
            AgentTask saved = taskRepo.save(t);
            scheduleRuns.onTaskTerminal(saved);
            n++;
        }
        if (n > 0) log.warn("Timeout enforcement: cancelled {} task(s) exceeding their configured timeout", n);
        return n;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AgentTask load(Long id) {
        return taskRepo.findById(id).orElseThrow(() -> NotFoundException.of("agent task", id));
    }

    private AgentTask loadForAgent(Long agentId, Long id) {
        return taskRepo.findByIdAndAgentId(id, agentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Task " + id + " not assigned to this agent"));
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

}
