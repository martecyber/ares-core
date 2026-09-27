package com.martecyber.ares.agents.schedule.dto;

import com.martecyber.ares.agents.schedule.AgentScheduleRun;

import java.time.OffsetDateTime;

public record AgentScheduleRunDto(
    Long id,
    Long scheduleId,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    int taskCount,
    int completedCount,
    int failedCount,
    Long totalDurationMs
) {
    public static AgentScheduleRunDto from(AgentScheduleRun r) {
        return new AgentScheduleRunDto(
            r.getId(), r.getScheduleId(),
            r.getStartedAt(), r.getCompletedAt(),
            r.getTaskCount(), r.getCompletedCount(), r.getFailedCount(),
            r.getTotalDurationMs()
        );
    }
}
