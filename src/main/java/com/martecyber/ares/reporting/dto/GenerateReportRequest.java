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
    Map<String, String> customFields
) {}
