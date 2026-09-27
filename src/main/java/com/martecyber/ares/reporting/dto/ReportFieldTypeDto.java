package com.martecyber.ares.reporting.dto;

import com.martecyber.ares.reporting.ReportFieldType;
import com.martecyber.ares.reporting.ReportFieldTemplate;
import java.time.OffsetDateTime;
import java.util.List;

public record ReportFieldTypeDto(
    Long id,
    String name,
    String label,
    String description,
    int sortOrder,
    boolean required,
    List<FieldTemplateDto> templates,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public record FieldTemplateDto(Long id, String name, String content, boolean isDefault,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static FieldTemplateDto from(ReportFieldTemplate t) {
            return new FieldTemplateDto(t.getId(), t.getName(), t.getContent(),
                t.isDefault(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public static ReportFieldTypeDto from(ReportFieldType t, List<ReportFieldTemplate> templates) {
        return new ReportFieldTypeDto(t.getId(), t.getName(), t.getLabel(),
            t.getDescription(), t.getSortOrder(), t.isRequired(),
            templates.stream().map(FieldTemplateDto::from).toList(),
            t.getCreatedAt(), t.getUpdatedAt());
    }
}
