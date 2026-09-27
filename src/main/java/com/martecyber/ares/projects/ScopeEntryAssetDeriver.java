package com.martecyber.ares.projects;

import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.imports.AssetImportHelper;
import com.martecyber.ares.imports.ParsedAsset;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives Asset objects from ProjectScopeEntry records.
 *
 * Only inScope=true entries are derived. Unsupported kinds (ip_wildcard, other)
 * return empty and are counted as skipped.
 */
@Service
public class ScopeEntryAssetDeriver {

    private final ProjectRepository projectRepo;
    private final ProjectScopeEntryRepository scopeRepo;
    private final AssetImportHelper importHelper;
    private final ProjectAssetAccessService accessSvc;
    private final AssetRepository assetRepo;
    private final AssetService assetService;

    public ScopeEntryAssetDeriver(ProjectRepository projectRepo,
                                   ProjectScopeEntryRepository scopeRepo,
                                   AssetImportHelper importHelper,
                                   ProjectAssetAccessService accessSvc,
                                   AssetRepository assetRepo,
                                   AssetService assetService) {
        this.projectRepo = projectRepo;
        this.scopeRepo      = scopeRepo;
        this.importHelper   = importHelper;
        this.accessSvc      = accessSvc;
        this.assetRepo      = assetRepo;
        this.assetService   = assetService;
    }

    public record DeriveResult(int derived, int skipped) {}

    /**
     * Derives a single scope entry into an asset and registers it in the project.
     * Returns true if an asset was created or found; false if the entry is not derivable
     * (unsupported kind or inScope=false).
     */
    public boolean derive(ProjectScopeEntry entry, Long organizationId) {
        if (!entry.isInScope()) return false;

        Optional<DeriveMapping> mapping = mapEntry(entry);
        if (mapping.isEmpty()) return false;

        DeriveMapping m = mapping.get();
        Long assetId = importHelper.resolveOrCreate(organizationId,
            new ParsedAsset(m.identifier(), m.assetType(), m.metadata()));

        // NETWORK assets need explicit auto-linking of existing IPs (AssetImportHelper
        // bypasses AssetService.create(), so the auto-link doesn't run automatically).
        if (AssetType.NETWORK.equals(m.assetType())) {
            assetRepo.findById(assetId)
                .ifPresent(net -> assetService.autoLinkNetwork(organizationId, net));
        } else if (AssetType.WEB_APPLICATION.equals(m.assetType())) {
            extendWebApp(assetId, m.identifier(), m.metadata(), organizationId, entry.getProjectId());
        }

        accessSvc.add(entry.getProjectId(), assetId);
        return true;
    }

    /**
     * After creating a WEB_APPLICATION, also creates:
     *  - A root WEB_ENDPOINT with the same URL linked via WEBAPP_ENDPOINT.
     *    The type difference (web_application vs web_endpoint) prevents identifier collision
     *    in resolveOrCreate (which now does type-aware lookup).
     *  - A DOMAIN asset for the hostname (if not a bare IP) linked via WEBAPP_DOMAIN.
     * All assets are registered in ProjectAssetAccess.
     */
    private void extendWebApp(Long webappId, String webappUrl, Map<String, Object> webappMeta,
                               Long organizationId, Long projectId) {
        String rootPath = (String) webappMeta.getOrDefault("rootPath", "/");

        // Root endpoint: same URL as the webapp (different type → no collision)
        Long endpointId = importHelper.resolveOrCreate(organizationId,
            new ParsedAsset(webappUrl, AssetType.WEB_ENDPOINT, Map.of("path", rootPath)));
        importHelper.linkIfAbsent(webappId, endpointId, AssetLinkType.WEBAPP_ENDPOINT);
        accessSvc.add(projectId, endpointId);

        // Domain: extract host from the URL, skip if bare IP
        String host = extractHost(webappUrl);
        if (host != null && !isIpAddress(host)) {
            Long domainId = importHelper.resolveOrCreate(organizationId,
                new ParsedAsset(host, AssetType.DOMAIN, Map.of()));
            importHelper.linkIfAbsent(webappId, domainId, AssetLinkType.WEBAPP_DOMAIN);
            accessSvc.add(projectId, domainId);
        }
    }

    private static String extractHost(String url) {
        try { return new URI(url).getHost(); }
        catch (Exception e) { return null; }
    }

    private static boolean isIpAddress(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * Re-derives all scope entries for an project. Idempotent — safe to call
     * multiple times; existing assets and access records are reused.
     */
    public DeriveResult deriveAll(Long projectId) {
        Project eng = projectRepo.findById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));

        List<ProjectScopeEntry> entries =
            scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId);

        int derived = 0, skipped = 0;
        for (ProjectScopeEntry entry : entries) {
            if (derive(entry, eng.getOrganizationId())) derived++;
            else skipped++;
        }
        return new DeriveResult(derived, skipped);
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private record DeriveMapping(String assetType, String identifier, Map<String, Object> metadata) {}

    private Optional<DeriveMapping> mapEntry(ProjectScopeEntry entry) {
        String kind  = entry.getKind();
        String value = entry.getValue();
        return switch (kind) {
            case "domain" ->
                Optional.of(new DeriveMapping(AssetType.DOMAIN, value, Map.of()));
            case "domain_wildcard" -> deriveDomainWildcard(value);
            case "url"          -> parseUrlMapping(value, false);
            case "url_wildcard" -> parseUrlMapping(stripUrlWildcard(value), true);
            case "ip"           -> Optional.of(new DeriveMapping(AssetType.IP, value, Map.of()));
            case "cidr"         -> Optional.of(new DeriveMapping(AssetType.NETWORK, value, Map.of()));
            case "android_app"  -> Optional.of(new DeriveMapping(AssetType.ANDROID_APP, value, Map.of()));
            case "ios_app"      -> Optional.of(new DeriveMapping(AssetType.IOS_APP, value, Map.of()));
            case "windows_app"  -> Optional.of(new DeriveMapping(AssetType.WINDOWS_APP, value, Map.of()));
            case "hardware"     -> Optional.of(new DeriveMapping(AssetType.HARDWARE, value, Map.of()));
            default             -> Optional.empty(); // ip_wildcard, other — not derivable
        };
    }

    /**
     * Handles domain_wildcard entries:
     *  - "*.example.*"           → TLD is wildcarded → skip (no domain asset).
     *  - "sub2.*.example.com"    → intermediate wildcard → derive "example.com".
     *  - "*.example.com"         → standard wildcard → derive "example.com".
     */
    private Optional<DeriveMapping> deriveDomainWildcard(String value) {
        // Strip leading "*." if present for analysis
        String stripped = value.startsWith("*.") ? value.substring(2) : value;

        // TLD wildcard: the part after the last dot is "*" or the whole thing has a trailing ".*"
        if (stripped.endsWith(".*") || stripped.equals("*")) {
            return Optional.empty(); // e.g., "*.example.*" or "*"
        }

        // Intermediate wildcard: remaining string still contains "*"
        if (stripped.contains("*")) {
            // Extract the base domain: everything after the last "*." or "*"
            int lastStar = stripped.lastIndexOf('*');
            String afterStar = stripped.substring(lastStar + 1);
            // Remove a leading "." if present
            if (afterStar.startsWith(".")) afterStar = afterStar.substring(1);
            if (afterStar.isBlank()) return Optional.empty();
            return Optional.of(new DeriveMapping(AssetType.DOMAIN, afterStar, Map.of()));
        }

        // Standard: "*.example.com" → stripped = "example.com"
        return Optional.of(new DeriveMapping(AssetType.DOMAIN, stripped, Map.of("wildcard", true)));
    }

    /**
     * Parses a URL into a WEB_APPLICATION mapping.
     * The identifier is the full URL (so each distinct scoped URL is a separate webapp).
     * The path is stored in metadata as rootPath.
     */
    private Optional<DeriveMapping> parseUrlMapping(String url, boolean wildcard) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) return Optional.empty();

            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";

            Map<String, Object> meta = wildcard
                ? Map.of("rootPath", path, "wildcard", true)
                : Map.of("rootPath", path);

            return Optional.of(new DeriveMapping(AssetType.WEB_APPLICATION, url, meta));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Removes trailing wildcard patterns from a URL: /*, *, etc. */
    private String stripUrlWildcard(String value) {
        return value.replaceAll("/\\*.*$", "").replaceAll("\\*$", "").stripTrailing();
    }
}
