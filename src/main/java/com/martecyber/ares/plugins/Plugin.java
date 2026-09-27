package com.martecyber.ares.plugins;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A plugin JAR installed on this instance — see {@link PluginLoader} for how its classes get
 * loaded and registered, and {@link PluginService} for install/enable/disable/uninstall.
 * {@code pluginId} is the plugin's own manifest id (its {@code plugin.json}'s {@code id}), not
 * this row's own {@code id} — that's what {@code IntegrationActionHandler#integrationType()} is
 * expected to match for a plugin providing one.
 */
@Entity
@Table(name = "plugin", schema = "ares")
public class Plugin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id", nullable = false, length = 100)
    private String pluginId;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(length = 150)
    private String vendor;

    @Column(length = 150)
    private String license;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sdk_version", nullable = false, length = 20)
    private String sdkVersion;

    /** A URL (external, like a vendor-hosted SVG) or an ares-ui-relative path (e.g. {@code
     *  "/img/sources/shodan.svg"}, for a tool that already ships a bundled icon there) — from the
     *  JAR's {@code plugin.json}. The default/dark-theme icon; null shows a generic fallback
     *  everywhere it's used. */
    @Column(columnDefinition = "TEXT")
    private String icon;

    /** Light-theme override for {@link #icon} — same convention {@code tool-icons.ts}'s own
     *  {@code LIGHT_ICON_SRC} already uses for built-in tools (e.g. Trivy), for a mark that only
     *  reads correctly against a dark background. Optional: most icons work on both themes and
     *  only set {@link #icon}; this is null in that case, and every consumer falls back to
     *  {@link #icon} on both themes when it is. */
    @Column(name = "icon_light", columnDefinition = "TEXT")
    private String iconLight;

    /** {@code "marketplace"} (installed from a vetted index) or {@code "upload"} (an admin's own
     *  JAR, of whatever provenance) — drives the "Verified"-badge distinction in the admin UI. */
    @Column(nullable = false, length = 20)
    private String source;

    /** The marketplace {@code downloadUrl} it came from, if {@code source == "marketplace"}. Null
     *  for an upload. */
    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    /** The {@link PluginRepositorySource} this was installed from, if any (null for an upload, or
     *  for a marketplace install made before this column existed). Lets "check for updates" go
     *  back to the exact repository that provided it rather than guessing from {@link #sourceUrl}
     *  alone. */
    @Column(name = "repository_source_id")
    private Long repositorySourceId;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    /** The JAR's filename under {@code ares.plugins.directory} on disk. */
    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "installed_by")
    private Long installedBy;

    @Column(name = "installed_at", nullable = false)
    private OffsetDateTime installedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Plugins this one requires to already be installed+enabled(+in-range) — see
     *  {@link PluginManifest}'s own doc comment. Empty for the common case of no dependencies. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "depends_on", columnDefinition = "jsonb", nullable = false)
    private List<PluginManifest.PluginDependency> dependsOn = List.of();

    /** FQCNs of interfaces this plugin defines for other (dependent) plugins to implement — see
     *  {@link PluginExtensionRegistry}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provides_extension_points", columnDefinition = "jsonb", nullable = false)
    private List<String> providesExtensionPoints = List.of();

    /** URL path prefixes this plugin's own {@link PluginRestController}s own — kept even while the
     *  plugin is disabled/not loaded, so a request to one of them can explain which plugin is
     *  missing instead of a bare 404. See {@code PluginFallbackController}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "owned_path_prefixes", columnDefinition = "jsonb", nullable = false)
    private List<String> ownedPathPrefixes = List.of();

    public Long getId() { return id; }

    public String getPluginId() { return pluginId; }
    public void setPluginId(String pluginId) { this.pluginId = pluginId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }

    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSdkVersion() { return sdkVersion; }
    public void setSdkVersion(String sdkVersion) { this.sdkVersion = sdkVersion; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getIconLight() { return iconLight; }
    public void setIconLight(String iconLight) { this.iconLight = iconLight; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Long getRepositorySourceId() { return repositorySourceId; }
    public void setRepositorySourceId(Long repositorySourceId) { this.repositorySourceId = repositorySourceId; }

    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getInstalledBy() { return installedBy; }
    public void setInstalledBy(Long installedBy) { this.installedBy = installedBy; }

    public OffsetDateTime getInstalledAt() { return installedAt; }
    public void setInstalledAt(OffsetDateTime installedAt) { this.installedAt = installedAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<PluginManifest.PluginDependency> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<PluginManifest.PluginDependency> dependsOn) { this.dependsOn = dependsOn == null ? List.of() : dependsOn; }

    public List<String> getProvidesExtensionPoints() { return providesExtensionPoints; }
    public void setProvidesExtensionPoints(List<String> providesExtensionPoints) { this.providesExtensionPoints = providesExtensionPoints == null ? List.of() : providesExtensionPoints; }

    public List<String> getOwnedPathPrefixes() { return ownedPathPrefixes; }
    public void setOwnedPathPrefixes(List<String> ownedPathPrefixes) { this.ownedPathPrefixes = ownedPathPrefixes == null ? List.of() : ownedPathPrefixes; }
}
