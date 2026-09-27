package com.martecyber.ares.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateRoleRequest(
    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Code must be uppercase alphanumeric with underscores")
    String code,
    @NotBlank String name,
    String description
) {}
