package com.martecyber.ares.agents.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background safety net for stuck agent tasks.
 *
 * The primary recovery path is heartbeat-driven: every heartbeat the agent reports
 * which task IDs it is actively running, and the server immediately resets any that
 * are not in that list (see {@link AgentTaskService#reconcileAgentTasks}).
 *
 * This scheduler is the fallback for the case where the agent itself has gone offline
 * entirely (crash without restart) and will never send another heartbeat. It resets
 * dispatched/running tasks assigned to silent agents so they can be picked up by any
 * other eligible agent in the pool.
 */
@Component
public class AgentTaskRecoveryScheduler {

    /** Agents missing this many minutes of heartbeats are considered offline. */
    private static final int OFFLINE_THRESHOLD_MINUTES = 5;

    private final AgentTaskService taskService;

    public AgentTaskRecoveryScheduler(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Runs every 2 minutes. Resets tasks held by agents that have been silent longer
     * than {@value OFFLINE_THRESHOLD_MINUTES} minutes back to {@code pending}.
     * Low overhead — a single UPDATE WHERE IN (SELECT ...) against the agent table.
     */
    @Scheduled(fixedDelay = 2 * 60_000, initialDelay = 60_000)
    public void recoverOrphanedTasks() {
        taskService.resetOrphanedTasks(OFFLINE_THRESHOLD_MINUTES);
    }
}
