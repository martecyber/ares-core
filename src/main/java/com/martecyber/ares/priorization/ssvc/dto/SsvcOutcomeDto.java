package com.martecyber.ares.priorization.ssvc.dto;

/** One entry of a role's reusable, named outcome palette — persisted directly on the
 *  role (see SsvcRole.outcomes) independent of whether any tree leaf currently uses it. */
public record SsvcOutcomeDto(
    String code,
    String label,
    String description,
    String priorityLevel
) {}
