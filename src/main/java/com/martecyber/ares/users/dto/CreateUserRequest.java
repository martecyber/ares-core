package com.martecyber.ares.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 2, max = 120) String displayName,
    @NotBlank @Size(min = 8, max = 128) String password
) {}
