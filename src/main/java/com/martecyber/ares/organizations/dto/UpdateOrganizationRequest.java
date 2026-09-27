package com.martecyber.ares.organizations.dto;

import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
    @Size(max = 120) String name,
    String status,
    String settings,
    SlaSettings sla
) {
    public record SlaSettings(
        Integer critical,
        Integer high,
        Integer medium,
        Integer low,
        Integer info
    ) {}
}
