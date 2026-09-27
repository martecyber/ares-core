package com.martecyber.ares.reporting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReportRequest(
    @NotNull Long organizationId,
    Long projectId,
    @NotBlank String type,
    String format,
    @NotBlank String title
) {}
