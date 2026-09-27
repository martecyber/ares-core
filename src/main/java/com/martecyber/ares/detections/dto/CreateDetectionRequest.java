package com.martecyber.ares.detections.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDetectionRequest(
    @NotNull Long projectId,
    Long assetId,
    @NotBlank String severity,
    @NotBlank String status,
    @NotBlank String title,
    String description,
    String rawData
) {}
