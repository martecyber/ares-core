package com.martecyber.ares.plugins;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.plugins.dto.PluginBrowseEntryDto;
import com.martecyber.ares.plugins.dto.PluginBrowseVersionDto;
import com.martecyber.ares.plugins.dto.RepositoryCatalogDto;
import com.martecyber.ares.plugins.dto.RepositoryPluginVersionsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Merges every enabled {@link PluginRepositorySource}'s own repository (see {@link
 * PluginRepositoryClient}) into one browsable, search-ready list, annotated with this instance's
 * own installed state and compatibility.
 *
 * <p>Repository layout expected at a repo's {@code baseUrl} (see {@code
 * ares-core/.../plugins/dto/RepositoryCatalogDto} and {@code RepositoryPluginVersionsDto} for the
 * exact JSON shapes), a plain static directory tree — no server logic on the hosting side:
 * <pre>
 *   /index.json                              catalog: every plugin id this repo carries
 *   /plugins/&lt;pluginId&gt;/index.json      full publish history for one plugin, newest first
 *   /plugins/&lt;pluginId&gt;/&lt;version&gt;/&lt;pluginId&gt;-&lt;version&gt;.jar
 * </pre>
 *
 * <p>One repository's own fetch failing (unreachable, malformed) never fails the whole browse —
 * it's logged and simply contributes nothing, so one bad user-added repository can't hide the
 * official one's plugins.
 */
@Service
public class PluginBrowseService {

    private static final Logger log = LoggerFactory.getLogger(PluginBrowseService.class);

    private final PluginRepositorySourceRepository repoSourceRepo;
    private final PluginRepository pluginRepo;
    private final PluginRepositoryClient client;

    // Field initializers double as the value a directly-`new`'d instance keeps (unit tests build
    // this class without Spring ever processing @Value) — same convention as PluginService's own
    // identical fields.
    @Value("${ares.versions.api:1.0.0-beta42}") private String apiVersion = "1.0.0-beta42";
    @Value("${ares.versions.ui:1.0.0-beta67}") private String uiVersion = "1.0.0-beta67";

    public PluginBrowseService(PluginRepositorySourceRepository repoSourceRepo, PluginRepository pluginRepo,
                                PluginRepositoryClient client) {
        this.repoSourceRepo = repoSourceRepo;
        this.pluginRepo = pluginRepo;
        this.client = client;
    }

    public List<PluginBrowseEntryDto> browse() {
        List<PluginRepositorySource> repos = repoSourceRepo.findAllByEnabledTrue();
        Map<String, Plugin> installedById = new LinkedHashMap<>();
        for (Plugin p : pluginRepo.findAll()) installedById.putIfAbsent(p.getPluginId(), p);

        List<CompletableFuture<List<PluginBrowseEntryDto>>> futures = repos.stream()
            .map(r -> CompletableFuture.supplyAsync(() -> browseOneRepo(r, installedById))
                .exceptionally(ex -> {
                    log.warn("Plugin repository '{}' ({}) could not be browsed: {}", r.getName(), r.getBaseUrl(), ex.getMessage());
                    return List.of();
                }))
            .toList();

        // First repository to list a given plugin id wins (repos are returned oldest-added-first,
        // so the official one — seeded first — naturally takes priority over a later duplicate).
        Map<String, PluginBrowseEntryDto> merged = new LinkedHashMap<>();
        for (var f : futures) for (var entry : f.join()) merged.putIfAbsent(entry.pluginId(), entry);
        return new ArrayList<>(merged.values());
    }

    /** Full version history of one plugin from one specific repository, each entry flagged
     *  {@code compatible} against this running instance. */
    public List<PluginBrowseVersionDto> browseVersions(Long repositorySourceId, String pluginId) {
        PluginRepositorySource r = repoSourceRepo.findById(repositorySourceId)
            .orElseThrow(() -> NotFoundException.of("plugin repository", repositorySourceId));
        RepositoryPluginVersionsDto versions = client.fetchPluginVersions(r.getBaseUrl(), pluginId, null);
        return versions.versions().stream()
            .map(v -> {
                boolean compatible = !v.yanked() && isCompatible(v);
                return new PluginBrowseVersionDto(v.version(), v.sdkVersion(), v.publishedAt(),
                    v.releaseNotes(), v.yanked(), compatible, compatible ? null : incompatibleReason(v));
            })
            .toList();
    }

    private List<PluginBrowseEntryDto> browseOneRepo(PluginRepositorySource r, Map<String, Plugin> installedById) {
        RepositoryCatalogDto catalog = client.fetchCatalog(r.getBaseUrl());
        List<PluginBrowseEntryDto> out = new ArrayList<>();
        for (var entry : catalog.plugins()) {
            RepositoryPluginVersionsDto versions;
            try {
                versions = client.fetchPluginVersions(r.getBaseUrl(), entry.id(), entry.path());
            } catch (Exception e) {
                log.warn("Plugin '{}' in repository '{}' could not be read: {}", entry.id(), r.getName(), e.getMessage());
                continue;
            }
            String latestCompatible = versions.versions().stream()
                .filter(v -> !v.yanked() && isCompatible(v))
                .map(RepositoryPluginVersionsDto.Version::version)
                .max(PluginVersions::compare)
                .orElse(null);

            Plugin installed = installedById.get(entry.id());
            boolean updateAvailable = installed != null && latestCompatible != null
                && PluginVersions.compare(latestCompatible, installed.getVersion()) > 0;

            out.add(new PluginBrowseEntryDto(entry.id(), entry.displayName(), entry.vendor(),
                entry.icon(), entry.iconLight(), versions.description(), r.getId(), r.getName(),
                entry.latestVersion(), latestCompatible, latestCompatible != null,
                installed != null ? installed.getVersion() : null, updateAvailable));
        }
        return out;
    }

    private boolean isCompatible(RepositoryPluginVersionsDto.Version v) {
        return PluginVersionRange.isInRange(apiVersion, v.minAresApiVersion(), v.maxAresApiVersion())
            && PluginVersionRange.isInRange(uiVersion, v.minAresUiVersion(), v.maxAresUiVersion());
    }

    private String incompatibleReason(RepositoryPluginVersionsDto.Version v) {
        if (v.yanked()) return "This version was yanked by its publisher";
        if (!PluginVersionRange.isInRange(apiVersion, v.minAresApiVersion(), v.maxAresApiVersion())) {
            return "Requires ares-core " + rangeLabel(v.minAresApiVersion(), v.maxAresApiVersion()) + " (running " + apiVersion + ")";
        }
        if (!PluginVersionRange.isInRange(uiVersion, v.minAresUiVersion(), v.maxAresUiVersion())) {
            return "Requires ares-ui " + rangeLabel(v.minAresUiVersion(), v.maxAresUiVersion()) + " (running " + uiVersion + ")";
        }
        return null;
    }

    private static String rangeLabel(String min, String max) {
        if (min != null && max != null) return min + "–" + max;
        if (min != null) return ">= " + min;
        if (max != null) return "<= " + max;
        return "any version";
    }
}
