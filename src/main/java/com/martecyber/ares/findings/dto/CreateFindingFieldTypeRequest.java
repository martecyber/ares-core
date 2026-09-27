package com.martecyber.ares.findings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateFindingFieldTypeRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9_]+$", message = "name must be lowercase alphanumeric and underscores only") String name,
    @NotBlank String title,
    String description,
    boolean required,
    int sortOrder
) {}
