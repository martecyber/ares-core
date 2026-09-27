package com.martecyber.ares.users.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record UserSummaryDto(
    Long id,
    String email,
    String displayName,
    String status,
    boolean mfaEnforced,
    boolean hasAvatar,
    List<String> roles,
    OffsetDateTime createdAt,
    /** Assigned holiday calendar (nullable). Surfaced so admins see/edit it in UserDialog. */
    Long holidayCalendarId,
    String holidayCalendarName
) {}
