package com.martecyber.ares.findings.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CreateFindingRequest(
    @NotNull Long projectId,
    @NotBlank String title,
    String severity,
    Long statusId,             // null → auto-selects "open"
    Long templateId,           // null → no template
    Boolean isDraft,           // null/true → draft (no code); false → assign code immediately
    List<@Valid FieldRequest> fields,
    List<@Valid ScoreRequest> scores,
    List<Long> referenceIds,
    @Valid AffectionRequest affection      // null → finding created with no affection yet
) {
    public record FieldRequest(
        @NotNull Long typeId,
        String fieldText
    ) {}

    public record ScoreRequest(
        @NotNull Long typeId,
        @NotNull BigDecimal score,
        String vector,
        Boolean isDefault,
        Long ssvcLeafNodeId
    ) {}

    public record AffectionRequest(
        String title,
        String description,
        List<Long> affectsIds,
        List<Long> detectedAtIds
    ) {}
}
