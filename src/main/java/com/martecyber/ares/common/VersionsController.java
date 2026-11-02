package com.martecyber.ares.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public versions endpoint. The UI shows api/cli/agent in its footer next to its own build
 * version; the agent uses {@code agent} to drive auto-update (mirrored by
 * {@code /agent/dist/version}). Values come from {@link PlatformVersions}.
 */
@RestController
@RequestMapping("/api/v1/versions")
public class VersionsController {

    private final PlatformVersions versions;
    private final PlatformSettingsService platformSettings;

    public VersionsController(PlatformVersions versions, PlatformSettingsService platformSettings) {
        this.versions = versions;
        this.platformSettings = platformSettings;
    }

    @GetMapping
    public Map<String, String> versions() {
        return Map.of("api", versions.api(), "cli", versions.cli(), "agent", versions.agent(),
                      "timezone", platformSettings.getTimezone());
    }
}
