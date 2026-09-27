package com.martecyber.ares.projects.dto;

import jakarta.validation.constraints.NotNull;

public record AddMemberRequest(
    @NotNull Long userId,
    String role
) {}
