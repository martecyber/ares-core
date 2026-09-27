package com.martecyber.ares.projects.dto;

import java.util.List;

public record ScopePropagationSourceDto(
    Long assetId,
    String identifier,
    String type,
    String relationshipType,
    String direction,        // "forward" | "reverse"
    String status,
    boolean override,
    List<ScopeEntryMatchDto> directMatches
) {}
