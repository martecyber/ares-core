package com.martecyber.ares.jobs.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
    @NotBlank String type,
    Long organizationId,
    String payload,
    Long projectId
) {}
