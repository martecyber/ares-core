package com.martecyber.ares.profile;

import jakarta.validation.constraints.NotNull;

public record CreateOooRequest(
    @NotNull String startDate,
    @NotNull String endDate,
    String reason
) {}
