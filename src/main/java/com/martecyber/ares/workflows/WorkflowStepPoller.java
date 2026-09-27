package com.martecyber.ares.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentTask;
import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.jobs.Job;
import com.martecyber.ares.jobs.JobRepository;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advances {@code WorkflowStepRun} rows stuck in {@code waiting} once the legacy-system row
 * they're anchored to (an {@code AgentTask} or {@code Job}) reaches a terminal state — see the
 * Workflows implementation plan's "Async completion" section for why this is a poller rather
 * than a new completion hook wired into {@code AgentTaskService}/{@code JobService}: zero changes
 * needed to either heavily-used existing service, at the cost of up to one poll interval of
 * latency, which is an acceptable tradeoff for a workflow engine (nobody needs sub-second
 * reaction to an agent task finishing). 15s, not the 60s used by {@code IntegrationScheduleService.
 * runDue()}, because step-advance latency is directly user-visible in the run view.
 * <p>
 * {@code ref_type="WORKFLOW_RUN"} (ACTION_CALL_WORKFLOW waiting on a child run) no longer exists
 * — ACTION_CALL_WORKFLOW is fire-and-forget broadcast only now, so it never leaves a step
 * {@code waiting}; only AGENT_TASK/JOB anchors are polled here.
 * <p>
 * Also runs {@link #reconcileStuckRuns}, a stuck-{@code running}-run sweep — a safety net, not
 * the normal completion path (that's {@link WorkflowRunService#advance} finishing its own node
 * loop synchronously, immediately when the last active step settles). It exists because a run's
 * final status update can be lost independently of its steps: every {@code WorkflowStepRun} row
 * (including a reached END node's) commits durably the moment {@code executeNode} returns (its
 * own REQUIRES_NEW transaction), but the owning {@code WorkflowRun} row's final {@code
 * COMPLETED}/{@code FAILED} update only commits when the enclosing {@code advance()} call itself
 * returns normally — if that specific call is ever interrupted (server restart mid-transaction, a
 * concurrent-instance race, any uncaught exception between two steps) after every step already
 * reached a terminal status, nothing else would ever call {@code advance()} for that run again, so
 * it would otherwise stay stuck at {@code running} forever despite having actually finished.
 */
@Component
public class WorkflowStepPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStepPoller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> AGENT_TASK_TERMINAL = Set.of("completed", "failed", "cancelled");
    private static final Set<String> JOB_TERMINAL = Set.of("completed", "failed", "cancelled");

    private final WorkflowStepRunRepository stepRunRepo;
    private final AgentTaskRepository agentTaskRepo;
    private final JobRepository jobRepo;
    private final WorkflowRunService runService;
    private final IntegrationActionRegistry integrationActionRegistry;

    public WorkflowStepPoller(WorkflowStepRunRepository stepRunRepo, AgentTaskRepository agentTaskRepo,
                               JobRepository jobRepo, WorkflowRunService runService,
                               IntegrationActionRegistry integrationActionRegistry) {
        this.stepRunRepo = stepRunRepo;
        this.agentTaskRepo = agentTaskRepo;
        this.jobRepo = jobRepo;
        this.runService = runService;
        this.integrationActionRegistry = integrationActionRegistry;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void pollWaitingSteps() {
        pollAgentTasks();
        pollJobs();
        pollIntegrationActions();
        reconcileStuckRuns();
    }

    /** See this class's own doc comment for why this exists. Re-invoking {@code advance()} is
     *  safe/idempotent here even in the (expected to be rare) case a run is caught mid-flight
     *  between two of its own steps rather than genuinely stuck — {@code advance()} just re-derives
     *  the same "nothing left to do" conclusion and returns without changing anything. */
    private void reconcileStuckRuns() {
        List<Long> stuckRunIds = stepRunRepo.findRunningWorkflowRunIdsWithNoActiveSteps();
        for (Long runId : stuckRunIds) {
            try {
                log.warn("Workflow run {} was stuck in 'running' with no active steps left — finalizing it now", runId);
                runService.advance(runId);
            } catch (Exception e) {
                log.error("Failed to reconcile stuck workflow run {}", runId, e);
            }
        }
    }

    private void pollAgentTasks() {
        List<WorkflowStepRun> waiting = stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "AGENT_TASK");
        if (waiting.isEmpty()) return;
        Map<Long, AgentTask> tasksById = agentTaskRepo.findAllById(waiting.stream().map(WorkflowStepRun::getRefId).toList())
            .stream().collect(java.util.stream.Collectors.toMap(AgentTask::getId, t -> t));
        for (WorkflowStepRun step : waiting) {
            AgentTask task = tasksById.get(step.getRefId());
            if (task == null || !AGENT_TASK_TERMINAL.contains(task.getStatus())) continue;
            try {
                if ("completed".equals(task.getStatus())) {
                    step.setStatus(WorkflowStepStatus.COMPLETED);
                    step.setOutput(writeJson(Map.of(
                        "taskId", task.getId(), "status", task.getStatus(),
                        "exitCode", task.getExitCode() == null ? -1 : task.getExitCode())));
                } else {
                    step.setStatus(WorkflowStepStatus.FAILED);
                    step.setError(task.getError() != null ? task.getError() : "Agent task " + task.getStatus());
                }
                step.setCompletedAt(OffsetDateTime.now());
                stepRunRepo.save(step);
                runService.mergeStepResultIntoContext(step.getWorkflowRunId(), step);
                runService.advance(step.getWorkflowRunId());
            } catch (Exception e) {
                log.error("Failed to advance workflow step {} for agent task {}", step.getId(), task.getId(), e);
            }
        }
    }

    private void pollJobs() {
        List<WorkflowStepRun> waiting = stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "JOB");
        if (waiting.isEmpty()) return;
        Map<Long, Job> jobsById = jobRepo.findAllById(waiting.stream().map(WorkflowStepRun::getRefId).toList())
            .stream().collect(java.util.stream.Collectors.toMap(Job::getId, j -> j));
        for (WorkflowStepRun step : waiting) {
            Job job = jobsById.get(step.getRefId());
            if (job == null || !JOB_TERMINAL.contains(job.getStatus())) continue;
            try {
                if ("completed".equals(job.getStatus())) {
                    step.setStatus(WorkflowStepStatus.COMPLETED);
                    step.setOutput(job.getResult() != null ? job.getResult() : "{}");
                } else {
                    step.setStatus(WorkflowStepStatus.FAILED);
                    step.setError(job.getError() != null ? job.getError() : "Job " + job.getStatus());
                }
                step.setCompletedAt(OffsetDateTime.now());
                stepRunRepo.save(step);
                runService.mergeStepResultIntoContext(step.getWorkflowRunId(), step);
                runService.advance(step.getWorkflowRunId());
            } catch (Exception e) {
                log.error("Failed to advance workflow step {} for job {}", step.getId(), job.getId(), e);
            }
        }
    }

    /** ACTION_INTEGRATION_CALL's own async anchor — unlike AGENT_TASK/JOB, the underlying row a
     *  step is waiting on can belong to any registered {@link IntegrationActionRegistry} handler
     *  (a Caido task today, something else entirely once a new handler ships), so which handler
     *  owns a given step is read back from the {@code integrationType} this class's own
     *  executeIntegrationCall stashed in the step's {@code input} column at start time — the
     *  {@code ref_id} itself is only ever meaningful together with that type. */
    private void pollIntegrationActions() {
        List<WorkflowStepRun> waiting = stepRunRepo.findByStatusAndRefType(WorkflowStepStatus.WAITING, "INTEGRATION_ACTION");
        if (waiting.isEmpty()) return;
        for (WorkflowStepRun step : waiting) {
            String integrationType = readIntegrationType(step);
            if (integrationType == null) {
                log.error("Workflow step {} waiting on INTEGRATION_ACTION has no integrationType in its input — skipping", step.getId());
                continue;
            }
            // Fails the step immediately rather than leaving it WAITING forever on silent
            // retries — an unregistered type is essentially never transient (it means "that
            // plugin isn't installed/enabled," see IntegrationActionRegistry#missingHandlerMessage),
            // so surfacing it now is more useful than a step that hangs until someone notices.
            if (!integrationActionRegistry.isRegistered(integrationType)) {
                try {
                    step.setStatus(WorkflowStepStatus.FAILED);
                    step.setError(integrationActionRegistry.missingHandlerMessage(integrationType));
                    step.setCompletedAt(OffsetDateTime.now());
                    stepRunRepo.save(step);
                    runService.mergeStepResultIntoContext(step.getWorkflowRunId(), step);
                    runService.advance(step.getWorkflowRunId());
                } catch (Exception e) {
                    log.error("Failed to fail workflow step {} for missing integration plugin (type {})",
                        step.getId(), integrationType, e);
                }
                continue;
            }
            IntegrationActionResult result;
            try {
                result = integrationActionRegistry.require(integrationType).checkStatus(step.getRefId());
            } catch (Exception e) {
                log.error("Failed to check status of integration action for workflow step {} (type {}, ref {})",
                    step.getId(), integrationType, step.getRefId(), e);
                continue;
            }
            if (IntegrationActionResult.RUNNING.equals(result.state())) continue;
            try {
                if (IntegrationActionResult.COMPLETED.equals(result.state())) {
                    step.setStatus(WorkflowStepStatus.COMPLETED);
                    step.setOutput(result.outputJson() != null ? result.outputJson() : "{}");
                } else {
                    step.setStatus(WorkflowStepStatus.FAILED);
                    step.setError(result.error() != null ? result.error() : "Integration action failed");
                }
                step.setCompletedAt(OffsetDateTime.now());
                stepRunRepo.save(step);
                runService.mergeStepResultIntoContext(step.getWorkflowRunId(), step);
                runService.advance(step.getWorkflowRunId());
            } catch (Exception e) {
                log.error("Failed to advance workflow step {} for integration action ref {}", step.getId(), step.getRefId(), e);
            }
        }
    }

    private String readIntegrationType(WorkflowStepRun step) {
        if (step.getInput() == null) return null;
        try {
            return MAPPER.readTree(step.getInput()).path("integrationType").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow step output", e);
        }
    }
}
