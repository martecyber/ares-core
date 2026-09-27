package com.martecyber.ares.kb.testing.dto;

import com.martecyber.ares.kb.testing.TestingGuide;

import java.time.OffsetDateTime;
import java.util.List;

public record TestingGuideDto(
    Long id,
    String name,
    String description,
    boolean enabled,
    long pointCount,
    Long creatorId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /** Populated on the detail endpoint only; null in list responses. */
    List<TestingGuidePointDto> points
) {
    public static TestingGuideDto summary(TestingGuide g, long pointCount) {
        return new TestingGuideDto(
            g.getId(), g.getName(), g.getDescription(), g.isEnabled(), pointCount,
            g.getCreatorId(), g.getCreatedAt(), g.getUpdatedAt(), null
        );
    }

    public static TestingGuideDto detail(TestingGuide g, List<TestingGuidePointDto> points) {
        return new TestingGuideDto(
            g.getId(), g.getName(), g.getDescription(), g.isEnabled(), points.size(),
            g.getCreatorId(), g.getCreatedAt(), g.getUpdatedAt(), points
        );
    }
}
