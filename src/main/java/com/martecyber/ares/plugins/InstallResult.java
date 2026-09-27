package com.martecyber.ares.plugins;

import com.martecyber.ares.plugins.dto.PluginDto;

/** Outcome of {@link PluginService#installFromUpload}/{@code installFromMarketplace} — a plain
 *  success, or (when a plugin with the same {@code plugin.json} id is already installed and the
 *  caller didn't pass {@code replace=true}) a description of how the new version compares to the
 *  installed one, for the admin UI to turn into a confirmation prompt instead of a flat error. */
public sealed interface InstallResult {

    record Installed(PluginDto plugin) implements InstallResult {}

    /** {@code conflict} is {@code "same"}, {@code "upgrade"}, or {@code "downgrade"} — see {@link
     *  PluginVersions#compare}. Nothing on disk or in the database changed; the caller re-submits
     *  the same install call with {@code replace=true} once the admin confirms (or does nothing,
     *  for {@code "same"}, since {@link PluginService} treats that as "nothing to confirm" too). */
    record VersionConflict(String conflict, String installedVersion, String newVersion) implements InstallResult {}
}
