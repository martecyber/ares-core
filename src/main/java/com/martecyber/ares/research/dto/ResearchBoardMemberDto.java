package com.martecyber.ares.research.dto;

import java.time.OffsetDateTime;

public record ResearchBoardMemberDto(
    Long userId,
    String email,
    String displayName,
    OffsetDateTime addedAt
) {}
