package com.martecyber.ares.assets.dto;

public record CreateWebEndpointHttpSampleRequest(
    String label,
    String requestContent,
    String responseContent,
    String notes
) {}
