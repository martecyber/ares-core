package com.martecyber.ares.projects.testing.dto;

import com.martecyber.ares.projects.testing.ProjectTestingGuide;

import java.time.OffsetDateTime;
import java.util.List;

public record ProjectTestingGuideDto(
    Long id,
    Long projectId,
    Long guideId,
    String name,
    OffsetDateTime assignedAt,
    List<ProjectTestingGuideItemDto> items
) {
    public static ProjectTestingGuideDto from(ProjectTestingGuide g, List<ProjectTestingGuideItemDto> items) {
        return new ProjectTestingGuideDto(
            g.getId(), g.getProjectId(), g.getGuideId(), g.getName(), g.getAssignedAt(), items
        );
    }
}
