package com.martecyber.ares.agents.tasks.dto;

import com.martecyber.ares.agents.tasks.AgentTask;

import java.time.OffsetDateTime;
import java.util.Map;

public final class AgentTaskDtos {

    private AgentTaskDtos() {}

    public record TaskDto(
        Long id,
        String name,
        Long projectId,
        Long poolId,
        Long agentId,
        /** Resolved agent display name — null when the task hasn't been claimed yet. */
        String agentName,
        String tool,
        String format,
        String args,
        String sourceIp,
        String nacProfile,
        String status,
        int priority,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime scheduledFor,
        OffsetDateTime dispatchedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Long scheduleId,
        Long scheduleRunId,
        Long actualDurationMs,
        Long importId,
        Integer exitCode,
        String error,
        Integer timeoutMinutes
    ) {
        public static TaskDto from(AgentTask t) { return from(t, null); }

        public static TaskDto from(AgentTask t, String agentName) {
            return new TaskDto(
                t.getId(), t.getName(), t.getProjectId(), t.getPoolId(), t.getAgentId(), agentName,
                t.getTool(), t.getFormat(), t.getArgs(), t.getSourceIp(), t.getNacProfile(),
                t.getStatus(), t.getPriority(), t.getCreatedBy(), t.getCreatedAt(),
                t.getScheduledFor(), t.getDispatchedAt(), t.getStartedAt(), t.getCompletedAt(),
                t.getScheduleId(), t.getScheduleRunId(),
                t.getActualDurationMs(),
                t.getImportId(), t.getExitCode(), t.getError(), t.getTimeoutMinutes()
            );
        }
    }

    /** Project user → server: create a one-shot task. */
    public record CreateTask(
        String name,
        Long poolId,
        String tool,
        String format,
        Map<String, Object> args,
        String nacProfile,
        Integer priority,
        /** Optional future timestamp — if set & in the future, task stays pending until then. */
        OffsetDateTime scheduledFor,
        /**
         * Optional batch size. When set and the resolved target list has more entries than
         * this value, the service creates one task per batch of N targets instead of one
         * large task. Null = no splitting.
         */
        Integer batchSize,
        /**
         * When true, skips enforcement of active time_window rules for this task.
         * Intended for passive scanning tools that are safe to run outside testing hours.
         */
        boolean bypassTimeWindow,
        /** Minutes after dispatch/start before this task is auto-cancelled. Null = no timeout. */
        Integer timeoutMinutes
    ) {}

    /** Global monitoring view: task with project / pool / agent display names resolved. */
    public record GlobalTaskDto(
        Long id,
        String name,
        Long projectId,
        String projectName,
        String projectCode,
        Long organizationId,
        Long poolId,
        String poolName,
        Long agentId,
        String agentName,
        String tool,
        String format,
        /** JSON string — same tool-specific field set the create form's config panel captured. */
        String args,
        String nacProfile,
        String status,
        int priority,
        OffsetDateTime createdAt,
        OffsetDateTime scheduledFor,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Long scheduleId,
        Long actualDurationMs,
        Integer exitCode,
        String error,
        Integer timeoutMinutes
    ) {
        public static GlobalTaskDto from(AgentTask t, String agentName, String poolName, String projectName, String projectCode, Long organizationId) {
            return new GlobalTaskDto(
                t.getId(), t.getName(),
                t.getProjectId(), projectName, projectCode, organizationId,
                t.getPoolId(), poolName,
                t.getAgentId(), agentName,
                t.getTool(), t.getFormat(), t.getArgs(), t.getNacProfile(),
                t.getStatus(), t.getPriority(),
                t.getCreatedAt(), t.getScheduledFor(), t.getStartedAt(), t.getCompletedAt(),
                t.getScheduleId(), t.getActualDurationMs(),
                t.getExitCode(), t.getError(), t.getTimeoutMinutes()
            );
        }
    }

    /** Agent ← server: descriptor of a claimed task. */
    public record TaskAssignment(
        Long id,
        String tool,
        String format,
        String args,
        Integer priority,
        Integer timeoutMinutes
    ) {
        public static TaskAssignment from(AgentTask t) {
            return new TaskAssignment(t.getId(), t.getTool(), t.getFormat(), t.getArgs(), t.getPriority(), t.getTimeoutMinutes());
        }
    }
}
