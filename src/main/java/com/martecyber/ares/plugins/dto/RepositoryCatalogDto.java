package com.martecyber.ares.plugins.dto;

import java.util.List;

/** Root {@code /index.json} of a plugin repository (see {@code PluginBrowseService}'s own doc
 *  comment for the full on-disk/on-bucket layout) — one cheap fetch listing every plugin the repo
 *  carries, each pointing at its own {@link RepositoryPluginVersionsDto} for the full version
 *  history. */
public record RepositoryCatalogDto(String repositoryName, String updatedAt, List<Entry> plugins) {

    public record Entry(String id, String displayName, String vendor, String icon,
                         String iconLight, String latestVersion, String path) {}
}
