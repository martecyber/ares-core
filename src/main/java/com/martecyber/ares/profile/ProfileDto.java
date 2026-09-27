package com.martecyber.ares.profile;

import java.util.List;

public record ProfileDto(
    Long id,
    String email,
    String displayName,
    boolean hasAvatar,
    List<String> roles,
    /** Currently-assigned holiday calendar (nullable when unset). */
    Long holidayCalendarId,
    String holidayCalendarName
) {}
