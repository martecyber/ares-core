package com.martecyber.ares.agents.schedule;

import com.martecyber.ares.agents.tasks.AgentTask;
import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Manages the lifecycle of {@link AgentScheduleRun} rows: one row per cron fire
 * of an {@link AgentTaskSchedule}, grouping the N tasks the schedule emits in
 * that execution. The run is closed (completedAt set, totalDurationMs computed)
 * when every task reaches a terminal state.
 *
 * Called from:
 *   • {@code AgentTaskScheduleService.enqueueOne} → {@link #startRun(Long, int)}
 *   • {@code AgentTaskService.complete/fail}      → {@link #onTaskTerminal(AgentTask)}
 */
@Service
public class AgentScheduleRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleRunService.class);
    private static final int AVG_WINDOW = 10;

    private final AgentScheduleRunRepository runRepo;
    private final AgentTaskScheduleRepository scheduleRepo;
    private final AgentTaskRepository taskRepo;

    public AgentScheduleRunService(AgentScheduleRunRepository runRepo,
                                    AgentTaskScheduleRepository scheduleRepo,
                                    AgentTaskRepository taskRepo) {
        this.runRepo = runRepo;
        this.scheduleRepo = scheduleRepo;
        this.taskRepo = taskRepo;
    }

    /**
     * Creates a new run row for the given schedule. The caller stamps the
     * returned id onto each task it creates so {@link #onTaskTerminal} can
     * find them later.
     */
    @Transactional
    public AgentScheduleRun startRun(Long scheduleId, int taskCount) {
        AgentScheduleRun r = new AgentScheduleRun();
        r.setScheduleId(scheduleId);
        r.setStartedAt(OffsetDateTime.now());
        r.setTaskCount(taskCount);
        return runRepo.save(r);
    }

    /**
     * Bumps the run counters and, when every task is terminal, closes the run
     * and updates the parent schedule's last/avg duration columns.
     *
     * Best-effort: errors are logged and swallowed so the underlying task
     * commit isn't rolled back.
     */
    @Transactional
    public void onTaskTerminal(AgentTask task) {
        Long runId = task.getScheduleRunId();
        if (runId == null) return;
        String status = task.getStatus();
        int completedDelta = "completed".equals(status) ? 1 : 0;
        int failedDelta    = ("failed".equals(status) || "cancelled".equals(status)) ? 1 : 0;
        if (completedDelta + failedDelta == 0) return;

        try {
            runRepo.incrementCounters(runId, completedDelta, failedDelta);
            AgentScheduleRun run = runRepo.findById(runId).orElse(null);
            if (run == null || run.getCompletedAt() != null) return;
            int taskCount = run.getTaskCount();
            if (taskCount > 0
                && (run.getCompletedCount() + run.getFailedCount()) >= taskCount) {
                closeRun(run);
            }
        } catch (Exception e) {
            log.warn("Failed to update agent_schedule_run {} for task {}: {}",
                runId, task.getId(), e.getMessage());
        }
    }

    /**
     * Stamps completedAt + totalDurationMs (max(completedAt) - min(startedAt) over
     * the run's tasks) and refreshes the parent schedule's summary columns.
     */
    private void closeRun(AgentScheduleRun run) {
        Object[] rawRange = taskRepo.timestampRangeForRun(run.getId());
        // Native query returns a single row wrapped as Object[][1] under some
        // Hibernate versions; unwrap to the inner row when needed.
        Object[] range = (rawRange != null && rawRange.length > 0 && rawRange[0] instanceof Object[])
            ? (Object[]) rawRange[0]
            : rawRange;
        OffsetDateTime minStarted   = toOffsetDateTime(range != null && range.length > 0 ? range[0] : null);
        OffsetDateTime maxCompleted = toOffsetDateTime(range != null && range.length > 1 ? range[1] : null);
        Long total = null;
        if (minStarted != null && maxCompleted != null && !maxCompleted.isBefore(minStarted)) {
            total = Duration.between(minStarted, maxCompleted).toMillis();
        }

        run.setCompletedAt(OffsetDateTime.now());
        run.setTotalDurationMs(total);
        runRepo.save(run);

        if (total != null) {
            final Long finalTotal = total;
            final Long avg = runRepo.avgDurationOverWindow(run.getScheduleId(), AVG_WINDOW).orElse(null);
            scheduleRepo.findById(run.getScheduleId()).ifPresent(s -> {
                s.setLastRunDurationMs(finalTotal);
                s.setAvgRunDurationMs(avg);
                scheduleRepo.save(s);
            });
        }
    }

    /** Converts the various timestamp shapes JPA/JDBC may return into an OffsetDateTime. */
    private static OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        if (o instanceof java.time.Instant in) return in.atOffset(ZoneOffset.UTC);
        return null;
    }
}
