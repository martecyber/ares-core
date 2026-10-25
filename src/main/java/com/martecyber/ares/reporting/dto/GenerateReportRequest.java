package com.martecyber.ares.reporting.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record GenerateReportRequest(
    @NotNull Long projectId,
    @NotNull Long organizationId,
    Long templateId,
    String title,
    /**
     * Specific finding IDs to include — null (field entirely absent) falls back to
     * iterationFrom/iterationTo or is_ready_to_report findings; an explicit empty list means the
     * caller deliberately wants a report with no findings, not a fallback.
     */
    List<Long> findingIds,
    /**
     * MONITOR only: include only findings whose iteration_label is >= iterationFrom.
     * Ignored when findingIds is non-null.
     */
    String iterationFrom,
    /**
     * MONITOR only: include only findings whose iteration_label is <= iterationTo.
     * Ignored when findingIds is non-null.
     */
    String iterationTo,
    /** Custom field content: field type name (slug) → HTML content. */
    Map<String, String> customFields,
    /**
     * Extra, named finding sections besides the default one ({@code findingIds}). Each becomes its
     * own template variable (see {@link FindingSection}); null/empty keeps the single-list model.
     */
    List<FindingSection> findingSections
) {

    /** Backwards-compatible constructor for callers that don't use sections. */
    public GenerateReportRequest(Long projectId, Long organizationId, Long templateId, String title,
                                 List<Long> findingIds, String iterationFrom, String iterationTo,
                                 Map<String, String> customFields) {
        this(projectId, organizationId, templateId, title, findingIds, iterationFrom, iterationTo, customFields, null);
    }

    /**
     * One extra findings section. {@code variable} is the template variable name ({@code {{?variable}}}
     * loops over its findings, with {@code variableCount}/{@code variableEmpty}/{@code variableNotEmpty}
     * and {@code variableTitle} alongside); {@code label} is the human-readable section name, exposed
     * as {@code {{variableTitle}}}.
     */
    public record FindingSection(String variable, String label, List<Long> findingIds) {}
}
