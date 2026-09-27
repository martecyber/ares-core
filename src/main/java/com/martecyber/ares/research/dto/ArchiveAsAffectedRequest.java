package com.martecyber.ares.research.dto;

import jakarta.validation.constraints.NotNull;

/** The board's detections have already been escalated (linked to this affection and flipped to
 *  "affected") by the caller via {@code DetectionService.escalateBatch} — this just archives the
 *  board with the result, replacing the old server-driven escalateToFinding flow now that the
 *  escalation wizard resolves the finding/affection itself via the richer finding/affection
 *  endpoints before calling here. */
public record ArchiveAsAffectedRequest(
    @NotNull Long affectionId
) {}
