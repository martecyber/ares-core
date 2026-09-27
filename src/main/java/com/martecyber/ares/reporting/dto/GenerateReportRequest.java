package com.martecyber.ares.reporting.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record GenerateReportRequest(
    @NotNull Long projectId,
    @NotNull Long organizationId,
    Long templateId,
    String title,
    /** Specific finding IDs to include. If empty, falls back to is_ready_to_report findings. */
    List<Long> findingIds,
    /**
     * MONITOR only: include only findings whose iteration_label is >= iterationFrom.
     * Ignored when findingIds is non-empty.
     */
    String iterationFrom,
    /**
     * MONITOR only: include only findings whose iteration_label is <= iterationTo.
     * Ignored when findingIds is non-empty.
     */
    String iterationTo,
    /** Custom field content: field type name (slug) → HTML content. */
    Map<String, String> customFields
) {}
