package com.martecyber.ares.agents.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background safety net enforcing each agent task's own configured timeout (see
 * {@link AgentTask#getTimeoutMinutes()}). Independent of {@link AgentTaskRecoveryScheduler},
 * which handles agents going silent — this handles a task simply running too long on an
 * agent that's still alive and heartbeating normally.
 */
@Component
public class AgentTaskTimeoutScheduler {

    private final AgentTaskService taskService;

    public AgentTaskTimeoutScheduler(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    /** Runs every minute — cheap single SELECT when nothing is timed out. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cancelTimedOutTasks() {
        taskService.cancelTimedOutTasks();
    }
}
