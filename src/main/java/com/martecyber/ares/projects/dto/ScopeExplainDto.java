package com.martecyber.ares.projects.dto;

import java.util.List;

public record ScopeExplainDto(
    String status,
    boolean override,
    List<ScopeEntryMatchDto> directMatches,
    List<ScopePropagationSourceDto> propagationSources
) {}
