package com.martecyber.ares.detections.dto;

import jakarta.validation.constraints.NotBlank;

public record EscalateDetectionRequest(
    @NotBlank String mode,       // "new_finding" | "existing_finding"
    Long findingId,               // required when mode=existing_finding
    String title,                 // required when mode=new_finding
    String severity,              // optional when mode=new_finding (falls back to detection severity)
    Long projectId,            // required when mode=new_finding
    Long assetId                  // optional: asset to attach to the occurrence
) {}
