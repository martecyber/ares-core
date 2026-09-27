package com.martecyber.ares.projects.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateProjectRequest(
    @NotNull Long organizationId,
    @NotBlank String name,
    Long typeId,
    String code,
    LocalDate startDate,
    LocalDate endDate,
    Long ownerUserId,
    String iterationCadence,
    boolean autoAdvanceIterations
) {}
