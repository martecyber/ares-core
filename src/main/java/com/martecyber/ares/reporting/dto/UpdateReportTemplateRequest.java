package com.martecyber.ares.reporting.dto;

import com.martecyber.ares.reporting.PriorityColor;
import java.util.List;
import java.util.Map;

public record UpdateReportTemplateRequest(
    String name,
    String description,
    Boolean isGeneric,
    Boolean isActive,
    List<Long> projectTypeIds,
    List<VariableRequest> variables,
    String magicColor,
    Map<String, PriorityColor> priorityColors
) {
    public record VariableRequest(
        String variableName,
        String sourceType,
        String systemField,
        Long fieldTypeId
    ) {}
}
