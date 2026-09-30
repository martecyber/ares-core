package com.martecyber.ares.findings.dto;

import jakarta.validation.constraints.NotBlank;

public record MoveIterationRequest(
    @NotBlank String iterationLabel
) {}
