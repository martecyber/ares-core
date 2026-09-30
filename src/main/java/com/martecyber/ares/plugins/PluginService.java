package com.martecyber.ares.plugins;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.plugins.dto.PluginDto;
import com.martecyber.ares.plugins.dto.RepositoryPluginVersionsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Install/enable/disable/uninstall lifecycle for plugin JARs — see {@link PluginLoader} for how
 * an installed, enabled plugin's classes actually become live inside the running app. {@code
 * ares.plugins.directory} (default {@code plugins/}, override via {@code ARES_PLUGINS_DIRECTORY})
 * is where every installed JAR lives on disk; the {@code plugin} table (see {@link Plugin}) is the
 * source of truth for what's installed and whether it's enabled — {@link
 * com.martecyber.ares.startup.StartupCleanupService} calls {@link #loadInstalledPlugins} once at
 * boot to load every enabled row's JAR.
 *
 * <p>Uninstalling (see {@link #uninstall}) and installing over an existing version (see {@link
 * #install}) both take effect immediately — no restart needed. See {@link PluginLoader}'s own doc
 * comment for why deleting a still-referenced JAR file and swapping a plugin's registration are
 * both safe to do live.
 *
 * <p>The "SDK version" ({@link PluginManifest#sdkVersion()}) is an exact-match compatibility gate
 * against {@link #SUPPORTED_SDK_VERSION} — deliberately not a range/semver check yet: with a
 * single pilot plugin and no other consumers of the SPI outside this repo, there's no real
 * compatibility matrix to model. Widen this once the SPI has actually changed once and there's a
 * real "plugin built against an older SDK" case to support.
 *
 * <p>Plugin-to-plugin dependencies ({@link PluginManifest#dependsOn}): {@link #install} rejects a
 * plugin whose declared dependency isn't already installed+enabled, and {@link #setEnabled} /
 * {@link #uninstall} reject disabling/uninstalling a plugin something else currently depends on —
 * a bare block, not an automatic cascade, so a dependency's data/registrations never disappear out
 * from under a still-enabled dependent. {@link #loadInstalledPlugins} loads in dependency order
 * (a simple DFS-based topological sort — see {@link #topologicalOrder}), since {@link
 * PluginLoader#load} assumes every dependency is already loaded.
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);
    private static final String SUPPORTED_SDK_VERSION = "1";

    private final PluginRepository repo;
    private final PluginLoader loader;
    private final PluginRepositorySourceRepository repoSourceRepo;
    private final PluginRepositoryClient repoClient;
    private final String pluginsDirectory;
    private final HttpClient http;

    // Field initializers double as the value a directly-`new`'d instance keeps (unit tests build
    // this class without Spring ever processing @Value) — Spring still overrides them from
    // application.yml/env for a real bean, same default strings VersionsController itself uses.
    @Value("${ares.versions.api:1.0.0-beta42}") private String apiVersion = "1.0.0-beta42";
    @Value("${ares.versions.ui:1.0.0-beta67}") private String uiVersion = "1.0.0-beta67";

    public PluginService(PluginRepository repo, PluginLoader loader,
                          PluginRepositorySourceRepository repoSourceRepo, PluginRepositoryClient repoClient,
                          @Value("${ares.plugins.directory:plugins}") String pluginsDirectory) {
        this.repo = repo;
        this.loader = loader;
        this.repoSourceRepo = repoSourceRepo;
        this.repoClient = repoClient;
        this.pluginsDirectory = pluginsDirectory;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public List<PluginDto> list() {
        return repo.findAllByOrderByInstalledAtAsc().stream().map(PluginDto::from).toList();
    }

    /** Called once at boot by {@code StartupCleanupService} — loads every enabled row's JAR, in
     *  dependency order, so its handler(s) are registered before any Workflow could reference it. */
    public void loadInstalledPlugins() {
        for (Plugin p : topologicalOrder(repo.findAllByOrderByInstalledAtAsc())) {
            if (!p.isEnabled()) continue;
            try {
                File jar = jarFile(p.getFilename());
                PluginManifest manifest = loader.readManifest(jar);
                loader.load(manifest, jar);
            } catch (Exception e) {
                log.error("Failed to load plugin '{}' at boot: {}", p.getPluginId(), e.getMessage(), e);
            }
        }
    }

    public InstallResult installFromUpload(MultipartFile file, Long installedBy, boolean replace) {
        try {
            Path tmp = Files.createTempFile("ares-plugin-upload-", ".jar");
            file.transferTo(tmp);
            try {
                return install(tmp, "upload", null, null, null, installedBy, replace);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file: " + e.getMessage());
        }
    }

    public InstallResult installFromMarketplace(String downloadUrl, String expectedChecksumSha256, Long installedBy, boolean replace) {
        return installFromMarketplace(downloadUrl, expectedChecksumSha256, null, installedBy, replace);
    }

    private InstallResult installFromMarketplace(String downloadUrl, String expectedChecksumSha256,
                                                  Long repositorySourceId, Long installedBy, boolean replace) {
        try {
            Path tmp = Files.createTempFile("ares-plugin-download-", ".jar");
            try {
                download(downloadUrl, tmp);
                String actualChecksum = sha256(tmp);
                if (expectedChecksumSha256 != null && !expectedChecksumSha256.equalsIgnoreCase(actualChecksum)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Downloaded plugin's checksum does not match the repository index — refusing to install");
                }
                return install(tmp, "marketplace", downloadUrl, actualChecksum, repositorySourceId, installedBy, replace);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not download plugin: " + e.getMessage());
        }
    }

    /** Resolves {@code version}'s entry in {@code pluginId}'s own version history at the given
     *  repository (rather than trusting a downloadUrl/checksum the frontend cached), then
     *  delegates to {@link #installFromMarketplace}. Rejects a version this running instance's
     *  own ares-core/ares-ui version doesn't satisfy before ever downloading it — the repository
     *  index is the single source of truth for what's installable here, same as {@link
     *  PluginBrowseService} uses to annotate the browse list. */
    public InstallResult installFromRepository(Long repositorySourceId, String pluginId, String version,
                                                Long installedBy, boolean replace) {
        PluginRepositorySource source = repoSourceRepo.findById(repositorySourceId)
            .orElseThrow(() -> NotFoundException.of("plugin repository", repositorySourceId));
        RepositoryPluginVersionsDto versions = repoClient.fetchPluginVersions(source.getBaseUrl(), pluginId, null);
        RepositoryPluginVersionsDto.Version entry = versions.versions().stream()
            .filter(v -> version.equals(v.version()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Version '" + version + "' of plugin '" + pluginId + "' not found in repository '" + source.getName() + "'"));
        if (entry.yanked()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Version '" + version + "' of plugin '" + pluginId + "' was yanked by its publisher");
        }
        assertProductCompatible(pluginId, entry.minAresApiVersion(), entry.maxAresApiVersion(),
            entry.minAresUiVersion(), entry.maxAresUiVersion());
        String downloadUrl = repoClient.resolveDownloadUrl(source.getBaseUrl(), entry.downloadUrl());
        return installFromMarketplace(downloadUrl, entry.checksumSha256(), repositorySourceId, installedBy, replace);
    }

    /** {@code replace}: {@code false} (the normal first attempt) stops short and reports a {@link
     *  InstallResult.VersionConflict} instead of touching anything, whenever a plugin with the
     *  same manifest id is already installed — the caller (the admin UI) shows a same-version
     *  info modal or an upgrade/downgrade confirmation, then re-calls with {@code replace=true} to
     *  actually proceed (skipped entirely for "same", which has nothing useful to redo). */
    private InstallResult install(Path jarTmpFile, String source, String sourceUrl, String precomputedChecksum,
                                   Long repositorySourceId, Long installedBy, boolean replace) {
        File jarFile = jarTmpFile.toFile();
        PluginManifest manifest;
        try {
            manifest = loader.readManifest(jarFile);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid plugin JAR: " + e.getMessage());
        }
        if (manifest.id() == null || manifest.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "plugin.json is missing 'id'");
        }
        if (!SUPPORTED_SDK_VERSION.equals(manifest.sdkVersion())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Plugin '" + manifest.id() + "' targets SDK version '" + manifest.sdkVersion()
                    + "', this instance supports '" + SUPPORTED_SDK_VERSION + "'");
        }
        assertProductCompatible(manifest.id(), manifest.minAresApiVersion(), manifest.maxAresApiVersion(),
            manifest.minAresUiVersion(), manifest.maxAresUiVersion());
        for (PluginManifest.PluginDependency dependency : manifest.dependsOn()) {
            String depId = dependency.pluginId();
            Plugin dep = repo.findByPluginId(depId).orElse(null);
            if (dep == null) {
                throw new PluginDependencyException(manifest.id(), depId, false);
            }
            if (!dep.isEnabled()) {
                throw new PluginDependencyException(manifest.id(), depId, true);
            }
            if (!PluginVersionRange.isInRange(dep.getVersion(), dependency.minVersion(), dependency.maxVersion())) {
                throw PluginDependencyException.versionMismatch(manifest.id(), depId, dep.getVersion(),
                    dependency.minVersion(), dependency.maxVersion());
            }
        }

        Optional<Plugin> existingOpt = repo.findByPluginId(manifest.id());
        if (existingOpt.isPresent() && !replace) {
            Plugin existing = existingOpt.get();
            int cmp = PluginVersions.compare(manifest.version(), existing.getVersion());
            String conflict = cmp == 0 ? "same" : (cmp > 0 ? "upgrade" : "downgrade");
            return new InstallResult.VersionConflict(conflict, existing.getVersion(), manifest.version());
        }

        String checksum;
        try {
            checksum = precomputedChecksum != null ? precomputedChecksum : sha256(jarTmpFile);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not checksum plugin JAR");
        }

        String filename = manifest.id() + "-" + manifest.version() + ".jar";
        File dest = jarFile(filename);
        try {
            Files.createDirectories(dest.getParentFile().toPath());
            Files.copy(jarTmpFile, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store plugin JAR: " + e.getMessage());
        }

        // loader.install() is used (not loader.load()) for both a fresh install AND a
        // replace — it proves the new JAR loads before touching any previous registration for
        // this pluginId (see its own doc comment), so a bad new version never leaves the type
        // unregistered. Only once that has succeeded do we remove the previous DB row/file (if
        // any) — a plugin whose classes fail to load must not leave a "successfully installed"
        // row behind, and a replace's old version must not disappear before the new one proves
        // it actually works.
        try {
            loader.install(manifest, dest);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            log.error("Plugin '{}' failed to load", manifest.id(), e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }

        existingOpt.ifPresent(old -> {
            // Different version numbers normally produce different filenames (see `filename`
            // above) — only delete the old file if it's not the exact one `dest` just
            // (over)wrote, which happens when replace=true reinstalls the identical version.
            if (!old.getFilename().equals(filename)) {
                try { Files.deleteIfExists(jarFile(old.getFilename()).toPath()); }
                catch (IOException e) { log.warn("Could not delete previous JAR for plugin '{}': {}", old.getPluginId(), e.getMessage()); }
            }
            repo.delete(old);
        });

        Plugin p = new Plugin();
        p.setPluginId(manifest.id());
        p.setVersion(manifest.version());
        p.setDisplayName(manifest.displayName());
        p.setVendor(manifest.vendor());
        p.setLicense(manifest.license());
        p.setDescription(manifest.description());
        p.setSdkVersion(manifest.sdkVersion());
        p.setIcon(manifest.icon());
        p.setIconLight(manifest.iconLight());
        p.setSource(source);
        p.setSourceUrl(sourceUrl);
        p.setRepositorySourceId(repositorySourceId);
        p.setChecksumSha256(checksum);
        p.setFilename(filename);
        p.setEnabled(true);
        p.setInstalledBy(installedBy);
        p.setDependsOn(manifest.dependsOn());
        p.setProvidesExtensionPoints(manifest.providesExtensionPoints());
        p.setOwnedPathPrefixes(manifest.ownedPathPrefixes());
        OffsetDateTime now = OffsetDateTime.now();
        p.setInstalledAt(now);
        p.setUpdatedAt(now);
        return new InstallResult.Installed(PluginDto.from(repo.save(p)));
    }

    public PluginDto setEnabled(Long id, boolean enabled) {
        Plugin p = find(id);
        if (p.isEnabled() == enabled) return PluginDto.from(p);
        if (enabled) {
            for (PluginManifest.PluginDependency dependency : p.getDependsOn()) {
                String depId = dependency.pluginId();
                Plugin dep = repo.findByPluginId(depId).orElse(null);
                if (dep == null || !dep.isEnabled()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot enable '" + p.getPluginId() + "': it requires '" + depId + "', which is "
                            + (dep == null ? "not installed" : "disabled") + " — enable that first");
                }
            }
            try {
                File jar = jarFile(p.getFilename());
                loader.load(loader.readManifest(jar), jar);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read plugin.json: " + e.getMessage());
            }
        } else {
            assertNoDependents(p.getPluginId(), "disable");
            loader.unload(p.getPluginId());
        }
        p.setEnabled(enabled);
        p.setUpdatedAt(OffsetDateTime.now());
        return PluginDto.from(repo.save(p));
    }

    /** Removes the plugin immediately — its integration type stops being offered right away
     *  ({@link PluginLoader#forget}), its JAR is deleted from disk, and its row is deleted. No
     *  restart needed (see {@link PluginLoader}'s own doc comment for why deleting a still-open
     *  JAR file is safe). Refuses if another installed plugin currently depends on this one. */
    public void uninstall(Long id) {
        Plugin p = find(id);
        assertNoDependents(p.getPluginId(), "uninstall");
        deletePlugin(p);
    }

    /** Blocks disabling/uninstalling a plugin something else currently installed declares {@code
     *  dependsOn} against — deliberately not an automatic cascade, so a dependency's data/
     *  registrations never disappear out from under a still-enabled dependent without the admin
     *  explicitly dealing with the dependent first. */
    private void assertNoDependents(String pluginId, String action) {
        List<String> dependents = repo.findAll().stream()
            .filter(p -> p.getDependsOn().stream().anyMatch(d -> d.pluginId().equals(pluginId)))
            .map(Plugin::getPluginId)
            .toList();
        if (!dependents.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot " + action + " '" + pluginId + "': " + String.join(", ", dependents)
                    + " depend(s) on it — " + action + " those first");
        }
    }

    private void deletePlugin(Plugin p) {
        loader.forget(p.getPluginId());
        try {
            Files.deleteIfExists(jarFile(p.getFilename()).toPath());
        } catch (IOException e) {
            log.warn("Could not delete JAR for plugin '{}': {}", p.getPluginId(), e.getMessage());
        }
        repo.delete(p);
        log.info("Uninstalled plugin '{}'", p.getPluginId());
    }

    /** Rejects a plugin (or one specific dependency's requirement on it) whose declared
     *  min/max ares-core or ares-ui range this running instance doesn't satisfy — the same
     *  {@code ares.versions.api}/{@code ares.versions.ui} values {@code VersionsController}
     *  reports, checked here (not just annotated at browse time by {@link PluginBrowseService})
     *  so an incompatible plugin can never actually be installed, whether uploaded directly,
     *  installed from a repository, or — via {@code installFromRepository}'s own earlier check —
     *  never even downloaded in the first place. */
    private void assertProductCompatible(String pluginId, String minApi, String maxApi, String minUi, String maxUi) {
        if (!PluginVersionRange.isInRange(apiVersion, minApi, maxApi)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Plugin '" + pluginId + "' requires ares-core " + rangeLabel(minApi, maxApi)
                    + ", this instance runs " + apiVersion);
        }
        if (!PluginVersionRange.isInRange(uiVersion, minUi, maxUi)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Plugin '" + pluginId + "' requires ares-ui " + rangeLabel(minUi, maxUi)
                    + ", this instance runs " + uiVersion);
        }
    }

    private static String rangeLabel(String min, String max) {
        if (min != null && max != null) return min + "–" + max;
        if (min != null) return ">= " + min;
        if (max != null) return "<= " + max;
        return "any version";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Plugin find(Long id) {
        return repo.findById(id).orElseThrow(() -> NotFoundException.of("plugin", id));
    }

    private File jarFile(String filename) {
        return new File(pluginsDirectory, filename);
    }

    /** Simple DFS-based topological sort by {@link Plugin#getDependsOn()} — a dependency always
     *  ends up before its dependent(s) in the returned order. A cycle (shouldn't be reachable
     *  given {@link #install}'s own dependency check, but defensive regardless) breaks out of that
     *  one branch with a warning rather than looping forever; the cyclic plugin(s) simply won't
     *  appear before whichever one is visited first. */
    private List<Plugin> topologicalOrder(List<Plugin> all) {
        Map<String, Plugin> byId = new LinkedHashMap<>();
        for (Plugin p : all) byId.putIfAbsent(p.getPluginId(), p);
        List<Plugin> result = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        Set<String> visiting = new java.util.HashSet<>();
        for (Plugin p : all) visit(p, byId, visited, visiting, result);
        return result;
    }

    private void visit(Plugin p, Map<String, Plugin> byId, Set<String> visited, Set<String> visiting, List<Plugin> result) {
        if (visited.contains(p.getPluginId())) return;
        if (!visiting.add(p.getPluginId())) {
            log.warn("Plugin dependency cycle detected involving '{}' — skipping its ordering constraint", p.getPluginId());
            return;
        }
        for (PluginManifest.PluginDependency dependency : p.getDependsOn()) {
            Plugin dep = byId.get(dependency.pluginId());
            if (dep != null) visit(dep, byId, visited, visiting, result);
        }
        visiting.remove(p.getPluginId());
        visited.add(p.getPluginId());
        result.add(p);
    }

    private void download(String url, Path dest) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available on any JVM
        }
    }
}
