package com.martecyber.ares.organizations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
        message = "slug must be lowercase alphanumeric + hyphens, 2-63 chars, not starting with hyphen")
    String slug,
    String settings
) {}
