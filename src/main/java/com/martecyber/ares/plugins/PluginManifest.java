package com.martecyber.ares.plugins;

import java.util.List;

/**
 * Shape of the {@code plugin.json} every plugin JAR carries at its root — read by
 * {@link PluginLoader} before the JAR's classes are even touched, so install can reject a
 * malformed/incompatible plugin without ever running its code. A repository's own {@code
 * plugins/<id>/index.json} (see {@link PluginBrowseService}) carries almost this same shape per
 * published version, plus a {@code downloadUrl}/{@code checksumSha256}/{@code publishedAt}/
 * {@code releaseNotes}/{@code yanked} the index adds on top — that's the separate {@code
 * RepositoryPluginVersionsDto.Version}, not this record, since those fields don't exist inside
 * the JAR itself.
 */
public record PluginManifest(
    String id,
    String version,
    String displayName,
    String vendor,
    String license,
    String description,
    String sdkVersion,
    /** Default icon — used as-is on the dark theme (this app's default) and as the fallback on
     *  the light theme when {@link #iconLight} isn't set. */
    String icon,
    /** Light-theme override for {@link #icon}, for a mark that only reads correctly against a
     *  dark background — optional, most icons don't need one. */
    String iconLight,
    /** Plugins this one requires to already be installed, enabled, AND (when a range is given)
     *  at a compatible version — see {@link PluginService}'s dependency validation and
     *  {@link PluginClassLoader}'s cross-plugin delegation. Empty (never null after Jackson binds
     *  this record — see the compact constructor) for the common case of no dependencies. */
    List<PluginDependency> dependsOn,
    /** FQCNs of interfaces THIS plugin defines for other (dependent) plugins to implement — see
     *  {@link PluginExtensionRegistry}'s own doc comment for the full mechanism. Empty for a
     *  plugin that isn't itself an extension point for anything. */
    List<String> providesExtensionPoints,
    /** URL path prefixes this plugin's own {@link PluginRestController}s own (e.g. {@code
     *  "/api/v1/projects/*&#47;bug-hunting-program"}) — recorded even while the plugin is
     *  installed-but-disabled or its classes otherwise aren't currently loaded, so {@code
     *  PluginFallbackController} can still explain which plugin a request needs instead of a bare
     *  404. Empty for a plugin that registers no REST endpoints of its own. */
    List<String> ownedPathPrefixes,
    /** Inclusive bounds on the running instance's own product version (see {@code
     *  VersionsController}'s {@code api}/{@code ui} values — NOT {@link #sdkVersion}, which gates
     *  the plugin SPI itself, not the surrounding product). Either or both may be null for "no
     *  constraint on this side" — checked by {@link PluginService#install} and annotated (not
     *  enforced — install still re-checks) on every entry a repository index lists, so an
     *  incompatible version can't be installed from the marketplace browse UI either. */
    String minAresApiVersion,
    String maxAresApiVersion,
    String minAresUiVersion,
    String maxAresUiVersion
) {
    public PluginManifest {
        if (dependsOn == null) dependsOn = List.of();
        if (providesExtensionPoints == null) providesExtensionPoints = List.of();
        if (ownedPathPrefixes == null) ownedPathPrefixes = List.of();
    }

    /** One entry of {@link #dependsOn}: a required plugin id, optionally bounded to a version
     *  range (either bound null = unbounded on that side; both null = "any installed+enabled
     *  version is fine", the same as the old flat-string-list shape this replaced). */
    public record PluginDependency(String pluginId, String minVersion, String maxVersion) {}
}
