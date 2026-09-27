package com.martecyber.ares.users.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Size(min = 2, max = 120) String displayName,
    String status,
    Boolean mfaEnforced
) {}
