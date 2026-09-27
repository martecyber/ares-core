package com.martecyber.ares.findings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record FindingDto(
    Long id,
    Long projectId,
    String code,
    String severity,
    String title,
    Long statusId,
    String statusName,
    Long creatorId,
    boolean isDraft,
    boolean isReadyToReport,
    OffsetDateTime reportedAt,
    OffsetDateTime resolvedAt,
    LocalDate dueDate,
    String iterationLabel,
    List<FieldDto> fields,
    List<ScoreDto> scores,
    List<AffectionDto> affections,
    List<ReferenceDto> references,
    List<StatusHistoryDto> statusHistory,
    /** 'open' | 'closed' | null. Derived from affection statuses; null when draft or no affections. */
    String remediationStatus,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<com.martecyber.ares.tags.TagDto> tags
) {
    /** Attaches batch-loaded tags after the rest of the DTO is built — mirrors {@code
     *  DetectionDto.withTags}, since this record's other fields are already fully assembled by
     *  the time tags are looked up (a separate join-table query, not part of the main select). */
    public FindingDto withTags(List<com.martecyber.ares.tags.TagDto> tags) {
        return new FindingDto(id, projectId, code, severity, title, statusId, statusName, creatorId,
            isDraft, isReadyToReport, reportedAt, resolvedAt, dueDate, iterationLabel, fields, scores,
            affections, references, statusHistory, remediationStatus, createdAt, updatedAt, tags);
    }

    public record FieldDto(Long id, Long typeId, String typeTitle, String fieldText,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record ScoreDto(Long id, Long typeId, String typeTitle, BigDecimal score,
                           String vector, boolean isDefault, String comment, String metadata,
                           Long ssvcLeafNodeId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record AffectionDto(
        Long id,
        String code,
        String title,
        String description,
        List<DetectedAtRef> detectedAt,
        List<AffectRef> affects,
        /** 'open' | 'closed'. Derived from affect statuses. */
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        /** A scanner detection linked to this affection. */
        public record DetectionRef(Long id, String title, String status, String severity) {}

        /**
         * An asset where the vulnerability was detected, with the timestamp of
         * first observation. {@code affects} contains the high-level assets
         * the operator has explicitly linked to this detected_at within the
         * affection (the affect chain). Empty when no links exist.
         */
        public record DetectedAtRef(String id, String type, String identifier,
                                    OffsetDateTime observedAt,
                                    List<DetectionRef> detections,
                                    List<AffectRef> affects) {}

        /** A high-level asset impacted by this vulnerability, with its remediation status. */
        public record AffectRef(String id, String type, String identifier, String status) {}
    }

    public record ReferenceDto(Long id, Long catalogId, String catalogCode,
                               String title, String description, String url, String faviconUrl) {}

    public record StatusHistoryDto(Long findingId, Long findingStatusId,
                                   String statusName, OffsetDateTime changedAt) {}
}
