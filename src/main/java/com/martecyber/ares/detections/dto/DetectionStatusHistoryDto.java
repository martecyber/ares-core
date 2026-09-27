package com.martecyber.ares.detections.dto;

import java.time.OffsetDateTime;

public record DetectionStatusHistoryDto(
    Long id,
    String eventType,
    String fromStatus,
    String toStatus,
    Long changedBy,
    String changedByName,
    String note,
    OffsetDateTime changedAt
) {}
