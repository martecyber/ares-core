package com.martecyber.ares.detections.dto;

public record DetectionAffectionDto(
    Long affectionId,
    String affectionCode,
    String affectionTitle,
    String affectionDescription,
    String affectionStatus,
    Long findingId,
    String findingCode,
    String findingTitle,
    String findingSeverity,
    String findingStatusName
) {}
