package com.martecyber.ares.research.dto;

import com.martecyber.ares.detections.dto.DetectionDto;
import java.time.OffsetDateTime;
import java.util.List;

/** Full board detail — the board dialog's payload. */
public record ResearchBoardDetailDto(
    Long id,
    Long projectId,
    String title,
    String notes,
    Long leadUserId,
    String leadName,
    String status,
    String verdict,
    Long resultAffectionId,
    Long resultFindingId,
    List<ResearchBoardMemberDto> members,
    List<DetectionDto> detections,
    OffsetDateTime archivedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
