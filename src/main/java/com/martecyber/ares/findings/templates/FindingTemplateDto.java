package com.martecyber.ares.findings.templates;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record FindingTemplateDto(
    Long id,
    String severity,
    String title,
    Long creatorId,
    List<FieldDto> fields,
    List<ScoreDto> scores,
    List<ReferenceDto> references,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<com.martecyber.ares.tags.TagDto> tags
) {
    /** Attaches batch-loaded tags after the rest of the DTO is built. */
    public FindingTemplateDto withTags(List<com.martecyber.ares.tags.TagDto> tags) {
        return new FindingTemplateDto(id, severity, title, creatorId, fields, scores, references,
            createdAt, updatedAt, tags);
    }

    public record FieldDto(Long id, Long typeId, String fieldText, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record ScoreDto(Long id, Long typeId, BigDecimal score, String metadata, boolean isDefault,
                           Long ssvcLeafNodeId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record ReferenceDto(Long id, Long catalogId, String catalogCode, String title, String description,
                               String url, String faviconUrl) {}
}
