package com.martecyber.ares.research.dto;

import jakarta.validation.constraints.NotNull;

public record AddResearchBoardMemberRequest(@NotNull Long userId) {}
