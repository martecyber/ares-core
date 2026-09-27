package com.martecyber.ares.plugins.dto;

import com.martecyber.ares.plugins.PluginRepositorySource;

import java.time.OffsetDateTime;

public record PluginRepositorySourceDto(
    Long id,
    String name,
    String baseUrl,
    boolean official,
    boolean enabled,
    OffsetDateTime addedAt
) {
    public static PluginRepositorySourceDto from(PluginRepositorySource s) {
        return new PluginRepositorySourceDto(s.getId(), s.getName(), s.getBaseUrl(),
            s.isOfficial(), s.isEnabled(), s.getAddedAt());
    }
}
