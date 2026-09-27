package com.martecyber.ares.detections.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Generalizes {@link com.martecyber.ares.detections.DetectionService#escalate} to multiple
 * detections plus an existing-or-new affection choice (the single-detection, new-affection-only
 * original never gained that second axis, which is why the live UI never actually called it —
 * see the Research Boards plan). All {@code detectionIds} go to the SAME finding/affection, same
 * "all will be escalated together" semantics {@code EscalateDialog}'s batch mode already has.
 *
 * findingMode: "new_finding" | "existing_finding"
 * affectionMode: "new_affection" | "existing_affection"
 */
public record EscalateBatchRequest(
    @NotEmpty List<Long> detectionIds,
    String findingMode,
    Long findingId,
    String findingTitle,
    String findingSeverity,
    Long projectId,
    String affectionMode,
    Long affectionId,
    String affectionTitle,
    String affectionDescription
) {}
