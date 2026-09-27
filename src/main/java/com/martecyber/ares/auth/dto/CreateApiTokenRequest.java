package com.martecyber.ares.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record CreateApiTokenRequest(
    @NotBlank @Size(max = 50) String name,
    String scopes,
    OffsetDateTime expiresAt
) {}
