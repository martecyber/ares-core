package com.martecyber.ares.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public versions endpoint. The UI shows these in the footer / about menu; the agent
 * uses {@code agent} to drive auto-update (mirrored by {@code /agent/dist/version}).
 *
 * Defaults match the canonical sources:
 *   • api   ← pom.xml &lt;revision&gt;
 *   • cli   ← ares-cli/pyproject.toml + classpath ares_cli.py
 *   • agent ← ares-agent/ares_agent.py AGENT_VERSION + classpath copy
 *   • ui    ← ares-ui/package.json
 */
@RestController
@RequestMapping("/api/v1/versions")
public class VersionsController {

    @Value("${ares.versions.api:1.0.0-beta4}")   private String api;

    @Value("${ares.versions.ui:1.0.0-beta3}")   private String ui;
    @Value("${ares.versions.cli:1.0.0-beta4}")   private String cli;
    @Value("${ares.versions.agent:1.0.0-beta21}") private String agent;

    private final PlatformSettingsService platformSettings;

    public VersionsController(PlatformSettingsService platformSettings) {
        this.platformSettings = platformSettings;
    }

    @GetMapping
    public Map<String, String> versions() {
        return Map.of("api", api, "ui", ui, "cli", cli, "agent", agent,
                      "timezone", platformSettings.getTimezone());
    }
}
