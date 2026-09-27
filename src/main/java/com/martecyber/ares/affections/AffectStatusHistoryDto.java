package com.martecyber.ares.affections;

import java.time.OffsetDateTime;

public record AffectStatusHistoryDto(
    Long id,
    String fromStatus,
    String toStatus,
    Long changedBy,
    String changedByName,
    String note,
    OffsetDateTime changedAt
) {}
