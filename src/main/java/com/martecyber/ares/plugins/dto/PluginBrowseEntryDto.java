package com.martecyber.ares.plugins.dto;

/** One row of {@code GET /api/v1/plugins/browse} — a plugin known to at least one enabled
 *  repository, merged with this instance's own installed state. {@code installable} is false
 *  only when NO version in the plugin's whole history is compatible with this running instance —
 *  the enforcement point for "can't be installed from the repository either", independent of the
 *  install-time re-check in {@code PluginService#install}. */
public record PluginBrowseEntryDto(
    String pluginId,
    String displayName,
    String vendor,
    String icon,
    String iconLight,
    /** From the plugin's own {@code plugins/<id>/index.json} (see {@code
     *  RepositoryPluginVersionsDto}'s own doc comment) — already fetched to compute {@code
     *  latestCompatibleVersion} below, so the detail/preview panel gets this for free from the
     *  same {@code /plugins/browse} call, no second request needed. */
    String description,
    Long repositorySourceId,
    String repositoryName,
    String latestVersion,
    /** Highest version in the plugin's history compatible with this running instance, or null if
     *  none is. Null does not necessarily mean {@code latestVersion} itself is incompatible — an
     *  older compatible version may still exist and show up via the drill-down endpoint. */
    String latestCompatibleVersion,
    boolean installable,
    String installedVersion,
    boolean updateAvailable
) {}
