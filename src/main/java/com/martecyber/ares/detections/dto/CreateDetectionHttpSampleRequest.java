package com.martecyber.ares.detections.dto;

public record CreateDetectionHttpSampleRequest(
    String label,
    String requestContent,
    String responseContent,
    String notes
) {}
