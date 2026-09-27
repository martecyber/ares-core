package com.martecyber.ares.projects.testing.dto;

import com.martecyber.ares.projects.testing.ProjectTestingGuideItem;

import java.time.OffsetDateTime;

public record ProjectTestingGuideItemDto(
    Long id,
    Long projectTestingGuideId,
    Long guidePointId,
    String title,
    String description,
    int sortOrder,
    String status,
    String notes,
    OffsetDateTime updatedAt,
    Long updatedBy
) {
    public static ProjectTestingGuideItemDto from(ProjectTestingGuideItem i) {
        return new ProjectTestingGuideItemDto(
            i.getId(), i.getProjectTestingGuideId(), i.getGuidePointId(),
            i.getTitle(), i.getDescription(), i.getSortOrder(),
            i.getStatus(), i.getNotes(), i.getUpdatedAt(), i.getUpdatedBy()
        );
    }
}
