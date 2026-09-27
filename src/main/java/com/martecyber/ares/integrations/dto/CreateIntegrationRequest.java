package com.martecyber.ares.integrations.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateIntegrationRequest(
    /** Optional: only needed for detector_tool-linked integrations (legacy). */
    Long toolId,
    @NotBlank String name,
    /** Discriminator: "tenable", "qualys", etc. */
    @NotBlank String type,
    /** PLATFORM | ORGANIZATION */
    String scope,
    /** Required if scope=ORGANIZATION. */
    Long organizationId,
    /** Sensitive key-value pairs (access key, secret, password…). Encrypted before storage. */
    Map<String, String> credentials,
    /** Non-sensitive config (base URL, region, etc.) stored as JSON. */
    String settings
) {
    public String effectiveScope() { return scope != null ? scope : "PLATFORM"; }
}
