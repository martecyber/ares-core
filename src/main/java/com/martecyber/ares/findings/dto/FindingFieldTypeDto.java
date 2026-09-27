package com.martecyber.ares.findings.dto;

public record FindingFieldTypeDto(
    Long id,
    String name,
    String title,
    String description,
    boolean isSystem,
    boolean isRequired,
    int sortOrder
) {}
