package com.martecyber.ares.plugins.dto;

/** One row of {@code GET /api/v1/plugins/browse/{pluginId}/versions} — a single published
 *  version, annotated with whether THIS running instance can install it. */
public record PluginBrowseVersionDto(
    String version,
    String sdkVersion,
    String publishedAt,
    String releaseNotes,
    boolean yanked,
    boolean compatible,
    /** Human-readable reason {@code compatible} is false (e.g. "requires ares-core >= ..."),
     *  null when compatible. */
    String incompatibleReason
) {}
