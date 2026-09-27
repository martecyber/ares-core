package com.martecyber.ares.plugins.dto;

import com.martecyber.ares.plugins.PluginManifest;

import java.util.List;

/** {@code /plugins/<id>/index.json} of a plugin repository — the full publish history of one
 *  plugin, newest first. {@code downloadUrl} may be relative (resolved against the repository's
 *  own base URL) or absolute.
 *
 *  <p>{@code displayName}/{@code vendor}/{@code icon}/{@code iconLight}/{@code description} live
 *  at this top level (identity of the plugin itself, refreshed from its manifest on every
 *  publish) rather than per-{@link Version} — the only place a repository's own root {@code
 *  /index.json} catalog can source them from when it's rebuilt (see {@code rebuild_catalog.py} in
 *  ares-plugins/_admin-tools/) without needing the actual JAR. This file is written by a
 *  per-plugin, prefix-scoped credential (see ares-plugins/PUBLISHING.md's "Accepting a
 *  third-party plugin" section); the root catalog is deliberately not writable by that same
 *  credential, so it can only ever be rebuilt from files like this one. */
public record RepositoryPluginVersionsDto(
    String id, String displayName, String vendor, String icon, String iconLight, String description,
    List<Version> versions
) {

    public record Version(
        String version,
        String sdkVersion,
        String minAresApiVersion,
        String maxAresApiVersion,
        String minAresUiVersion,
        String maxAresUiVersion,
        List<PluginManifest.PluginDependency> dependsOn,
        String downloadUrl,
        String checksumSha256,
        String publishedAt,
        String releaseNotes,
        boolean yanked
    ) {}
}
