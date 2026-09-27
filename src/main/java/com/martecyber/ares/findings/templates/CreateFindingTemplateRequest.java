package com.martecyber.ares.findings.templates;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateFindingTemplateRequest(
    /** No longer authoritative — severity is derived from the default score, see
     *  {@link FindingTemplateService}. Kept for backward-compatible deserialization
     *  but ignored. */
    String severity,
    @NotBlank String title,
    List<FieldInput> fields,
    List<ScoreInput> scores,
    List<Long> referenceIds
) {
    public record FieldInput(Long typeId, String fieldText) {}
    public record ScoreInput(Long typeId, Double score, String metadata, Boolean isDefault, Long ssvcLeafNodeId) {}
}
