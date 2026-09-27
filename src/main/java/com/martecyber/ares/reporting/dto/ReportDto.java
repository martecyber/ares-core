package com.martecyber.ares.reporting.dto;

import com.martecyber.ares.reporting.Report;
import com.martecyber.ares.reporting.ReportField;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ReportDto(
    Long id,
    Long organizationId,
    Long projectId,
    String type,
    String format,
    String status,
    String title,
    Long templateId,
    Long fileId,
    boolean downloadable,
    Long generatedBy,
    String error,
    int findingCount,
    List<FieldValueDto> fieldValues,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    OffsetDateTime publishedAt
) {
    public record FieldValueDto(Long fieldTypeId, String fieldName, String content) {}

    public static ReportDto from(Report r) {
        return from(r, 0, List.of());
    }

    public static ReportDto from(Report r, int findingCount, List<ReportField> fields) {
        List<FieldValueDto> fieldValues = fields.stream()
            .map(f -> new FieldValueDto(f.getFieldTypeId(), f.getFieldName(), f.getContent()))
            .toList();
        return new ReportDto(r.getId(), r.getOrganizationId(), r.getProjectId(),
            r.getType(), r.getFormat(), r.getStatus(), r.getTitle(),
            r.getTemplateId(), r.getFileId(),
            r.getReportObjectKey() != null,
            r.getGeneratedBy(), r.getError(),
            findingCount, fieldValues,
            r.getCreatedAt(), r.getCompletedAt(), r.getPublishedAt());
    }
}
