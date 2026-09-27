package com.martecyber.ares.detections.dto;

import com.martecyber.ares.detections.DetectionHttpSample;
import java.time.OffsetDateTime;

public record DetectionHttpSampleDto(
    Long id,
    Long detectionId,
    String label,
    String requestContent,
    String responseContent,
    String notes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static DetectionHttpSampleDto from(DetectionHttpSample s) {
        return new DetectionHttpSampleDto(
            s.getId(), s.getDetectionId(), s.getLabel(),
            s.getRequestContent(), s.getResponseContent(), s.getNotes(),
            s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
