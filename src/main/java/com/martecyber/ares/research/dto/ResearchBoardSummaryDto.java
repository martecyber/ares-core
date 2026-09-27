package com.martecyber.ares.research.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** List-view shape — no notes/full detection payload, just enough to render a board row. */
public record ResearchBoardSummaryDto(
    Long id,
    Long projectId,
    String title,
    Long leadUserId,
    String leadName,
    String status,
    String verdict,
    Long resultAffectionId,
    /** Denormalized from the affection at read time (not stored) — lets the archived tab deep
     *  link straight to the finding without a second round-trip. */
    Long resultFindingId,
    List<ResearchBoardMemberDto> members,
    int detectionCount,
    OffsetDateTime archivedAt,
    OffsetDateTime updatedAt
) {}
