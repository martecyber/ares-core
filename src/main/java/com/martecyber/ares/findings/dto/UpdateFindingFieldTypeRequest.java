package com.martecyber.ares.findings.dto;

public record UpdateFindingFieldTypeRequest(
    String title,
    String description,
    Boolean required,
    Integer sortOrder
) {}
