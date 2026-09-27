package com.martecyber.ares.reporting.dto;

import com.martecyber.ares.reporting.ReportTemplate;
import com.martecyber.ares.reporting.ReportTemplateVariable;
import com.martecyber.ares.reporting.PriorityColor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ReportTemplateDto(
    Long id,
    String name,
    String description,
    String format,
    boolean isGeneric,
    boolean isActive,
    String originalFilename,
    Long createdBy,
    List<Long> projectTypeIds,
    List<VariableDto> variables,
    String magicColor,
    Map<String, PriorityColor> priorityColors,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public record VariableDto(
        Long id,
        String variableName,
        String sourceType,
        String systemField,
        Long fieldTypeId
    ) {
        public static VariableDto from(ReportTemplateVariable v) {
            return new VariableDto(v.getId(), v.getVariableName(), v.getSourceType(),
                v.getSystemField(), v.getFieldTypeId());
        }
    }

    public static ReportTemplateDto from(ReportTemplate t, List<Long> typeIds, List<ReportTemplateVariable> vars) {
        return new ReportTemplateDto(t.getId(), t.getName(), t.getDescription(), t.getFormat(),
            t.isGeneric(), t.isActive(), t.getOriginalFilename(), t.getCreatedBy(),
            typeIds,
            vars.stream().map(VariableDto::from).toList(),
            t.getMagicColor(),
            t.getPriorityColors(),
            t.getCreatedAt(), t.getUpdatedAt());
    }
}
