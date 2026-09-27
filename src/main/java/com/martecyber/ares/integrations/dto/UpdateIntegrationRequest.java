package com.martecyber.ares.integrations.dto;

import java.util.Map;

public record UpdateIntegrationRequest(
    String name,
    String status,
    String settings,
    /** When provided, re-encrypts and replaces stored credentials. */
    Map<String, String> credentials
) {}
