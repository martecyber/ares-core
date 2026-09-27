package com.martecyber.ares.kb.testing.dto;

import com.martecyber.ares.kb.testing.TestingGuidePoint;

import java.time.OffsetDateTime;

public record TestingGuidePointDto(
    Long id,
    Long guideId,
    String title,
    String description,
    int sortOrder,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static TestingGuidePointDto from(TestingGuidePoint p) {
        return new TestingGuidePointDto(
            p.getId(), p.getGuideId(), p.getTitle(), p.getDescription(),
            p.getSortOrder(), p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
