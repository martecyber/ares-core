package com.martecyber.ares.projects.dto;

import jakarta.validation.constraints.NotBlank;

public record AddScopeEntryRequest(
    @NotBlank String kind,
    @NotBlank String value,
    String notes,
    String metadata,
    /** Whether this entry is in scope. Defaults to true if null. */
    Boolean inScope
) {}
