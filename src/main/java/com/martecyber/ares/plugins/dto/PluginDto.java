package com.martecyber.ares.plugins.dto;

import com.martecyber.ares.plugins.Plugin;

import java.time.OffsetDateTime;

public record PluginDto(
    Long id,
    String pluginId,
    String version,
    String displayName,
    String vendor,
    String license,
    String description,
    String sdkVersion,
    String icon,
    String iconLight,
    String source,
    boolean enabled,
    OffsetDateTime installedAt
) {
    public static PluginDto from(Plugin p) {
        return new PluginDto(p.getId(), p.getPluginId(), p.getVersion(), p.getDisplayName(),
            p.getVendor(), p.getLicense(), p.getDescription(), p.getSdkVersion(), p.getIcon(),
            p.getIconLight(), p.getSource(), p.isEnabled(), p.getInstalledAt());
    }
}
