package com.martecyber.ares.jobs.dto;

import com.martecyber.ares.jobs.Job;
import java.time.OffsetDateTime;

public record JobDto(
    Long id,
    String type,
    String status,
    Long organizationId,
    Long projectId,
    Long createdBy,
    String payload,
    String result,
    String error,
    Integer progress,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt
) {
    public static JobDto from(Job j) {
        return new JobDto(j.getId(), j.getType(), j.getStatus(), j.getOrganizationId(),
            j.getProjectId(), j.getCreatedBy(), j.getPayload(), j.getResult(), j.getError(),
            j.getProgress(), j.getCreatedAt(), j.getUpdatedAt(), j.getStartedAt(), j.getCompletedAt());
    }
}
