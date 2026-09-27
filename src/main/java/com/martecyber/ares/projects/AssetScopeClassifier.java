package com.martecyber.ares.projects;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetRelationship;
import com.martecyber.ares.assets.AssetRelationshipRepository;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntry;
import com.martecyber.ares.kb.thirdparty.KbThirdPartyEntryRepository;
import com.martecyber.ares.projects.dto.ScopeEntryMatchDto;
import com.martecyber.ares.projects.dto.ScopeExplainDto;
import com.martecyber.ares.projects.dto.ScopePropagationSourceDto;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Classifies assets in an project as in_scope / out_of_scope / indeterminate
 * based on the project's scope entries and the relationship graph.
 *
 * Algorithm:
 *  1. Direct match — each asset is tested against every scope entry.
 *     An asset can receive both in_scope and out_of_scope signals (conflict → indeterminate).
 *  2. BFS propagation — each non-conflicting direct match becomes a seed.
 *     The seed's status is propagated through the undirected relationship graph
 *     to assets that have no direct match of their own.
 *  3. Final status — for each asset:
 *       - direct match (non-conflicting) → wins
 *       - no direct match, reachable only from in_scope seeds → in_scope
 *       - no direct match, reachable only from out_of_scope seeds → out_of_scope
 *       - no direct match, reachable from both → indeterminate
 *       - no direct match, unreachable → indeterminate
 *  4. HOST assets: scope is aggregated from their interfaces (see step 4 in classify()).
 *  5. Third-party KB check — an asset whose own identifier matches an enabled KB third-party
 *     entry becomes THIRD_PARTY, prevailing over whatever steps 1-4 computed for it. This never
 *     propagates through the relationship graph or HOST/interface aggregation.
 *  Assets with scopeOverride=true are skipped by all of the above (user decision stands).
 */
@Service
public class AssetScopeClassifier {

    public static final String IN_SCOPE      = "in_scope";
    public static final String OUT_OF_SCOPE  = "out_of_scope";
    public static final String INDETERMINATE = "indeterminate";
    public static final String THIRD_PARTY   = "third_party";

    /**
     * Relationship types where scope flows in the link direction (from-asset → to-asset).
     *
     * HOST assets are intentionally excluded from BFS propagation (neither HOST_INTERFACE
     * nor DOMAIN_HOST is listed here). A host's scope is computed in a separate Step 4
     * as the aggregate of its interfaces' scopes.
     */
    private static final Set<String> FORWARD_PROPAGATE = Set.of(
        AssetLinkType.DOMAIN_A,           // domain → ip  (A record: IP inherits domain scope)
        AssetLinkType.DOMAIN_AAAA,        // domain → ip  (AAAA record: IPv6 inherits domain scope)
        AssetLinkType.DOMAIN_CNAME,       // domain → domain (CNAME: target inherits source scope)
        AssetLinkType.DOMAIN_SRV,         // domain → service (SRV record)
        AssetLinkType.NETWORK_IP,         // network/cidr → ip
        AssetLinkType.NETWORK_SUBNET,     // network → subnet network
        AssetLinkType.INTERFACE_IP,       // interface → ip (ip inherits interface scope)
        AssetLinkType.INTERFACE_SERVICE,  // interface → service (service inherits interface scope)
        AssetLinkType.WEBAPP_ENDPOINT,    // web_application → web_endpoint
        AssetLinkType.ENDPOINT_CHILD      // web_endpoint → child web_endpoint
    );

    /**
     * Relationship types where scope flows AGAINST the link direction (to-asset → from-asset).
     *
     * DOMAIN_A reverse: ip in scope → domain that resolves to it (PTR / reverse DNS).
     * INTERFACE_IP reverse: ip in scope → interface that owns it.
     * WEBAPP_DOMAIN/SERVICE reverse: domain/service in scope → webapp that uses them.
     */
    private static final Set<String> REVERSE_PROPAGATE = Set.of(
        AssetLinkType.DOMAIN_A,        // ip in scope → domain with A record pointing to it
        AssetLinkType.DOMAIN_AAAA,     // ipv6 in scope → domain with AAAA record pointing to it
        AssetLinkType.INTERFACE_IP,    // ip in scope → interface that holds it
        AssetLinkType.WEBAPP_DOMAIN,   // domain in scope → webapp that uses it
        AssetLinkType.WEBAPP_SERVICE   // service in scope → webapp that runs on it
    );

    private final ProjectRepository            projectRepo;
    private final ProjectScopeEntryRepository  scopeRepo;
    private final ProjectAssetAccessRepository accessRepo;
    private final AssetRepository              assetRepo;
    private final AssetRelationshipRepository  relRepo;
    private final KbThirdPartyEntryRepository  kbThirdPartyRepo;

    public AssetScopeClassifier(ProjectRepository projectRepo,
                                 ProjectScopeEntryRepository scopeRepo,
                                 ProjectAssetAccessRepository accessRepo,
                                 AssetRepository assetRepo,
                                 AssetRelationshipRepository relRepo,
                                 KbThirdPartyEntryRepository kbThirdPartyRepo) {
        this.projectRepo      = projectRepo;
        this.scopeRepo        = scopeRepo;
        this.accessRepo       = accessRepo;
        this.assetRepo        = assetRepo;
        this.relRepo          = relRepo;
        this.kbThirdPartyRepo = kbThirdPartyRepo;
    }

    /** Re-classifies every project that contains the given asset. */
    @Transactional
    public void classifyForAsset(Long assetId) {
        accessRepo.findByAssetId(assetId).stream()
            .map(ProjectAssetAccess::getProjectId)
            .distinct()
            .forEach(this::classify);
    }

    @Transactional
    public int classify(Long projectId) {
        List<ProjectScopeEntry> entries   = scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
        List<KbThirdPartyEntry> kbEntries = kbThirdPartyRepo.findAllByEnabledTrue();
        List<ProjectAssetAccess> accesses = accessRepo.findByProjectId(projectId);
        if (accesses.isEmpty()) return 0;

        Set<Long> assetIds = accesses.stream().map(ProjectAssetAccess::getAssetId).collect(Collectors.toSet());
        Map<Long, Asset> assetsById = findAllByIdChunked(assetIds).stream()
            .collect(Collectors.toMap(Asset::getId, a -> a));

        // Build a DIRECTED propagation graph using only semantically meaningful edge types.
        // FORWARD_PROPAGATE: scope flows in the link direction (from → to).
        // REVERSE_PROPAGATE: scope flows against the link direction (to → from).
        // Relationship types not in either set (e.g. *_technology) are intentionally excluded
        // so that technology tags, system mappings, etc. do not inherit scope automatically.
        List<AssetRelationship> rels = relRepo.findByProjectId(projectId);
        Map<Long, Set<Long>> adj = new HashMap<>();
        for (AssetRelationship r : rels) {
            Long from = r.getFromAssetId();
            Long to   = r.getToAssetId();
            if (!assetIds.contains(from) || !assetIds.contains(to)) continue;
            String type = r.getType();
            if (FORWARD_PROPAGATE.contains(type)) {
                adj.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            }
            if (REVERSE_PROPAGATE.contains(type)) {
                adj.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        }

        // Step 1: direct matches — collect both in/out signals per asset
        Map<Long, Set<String>> directSignals = new HashMap<>();
        for (Long id : assetIds) directSignals.put(id, new HashSet<>());
        for (ProjectAssetAccess access : accesses) {
            Asset asset = assetsById.get(access.getAssetId());
            if (asset == null) continue;
            for (ProjectScopeEntry entry : entries) {
                if (matches(asset, entry)) {
                    directSignals.get(asset.getId()).add(entry.isInScope() ? IN_SCOPE : OUT_OF_SCOPE);
                }
            }
        }

        // Resolve direct status per asset (empty=none, size-1=clear, size-2=conflict)
        Map<Long, String> directStatus = new HashMap<>();
        for (Map.Entry<Long, Set<String>> e : directSignals.entrySet()) {
            Set<String> signals = e.getValue();
            if (signals.size() == 1) directStatus.put(e.getKey(), signals.iterator().next());
            else if (signals.size() > 1) directStatus.put(e.getKey(), INDETERMINATE);
        }

        // Step 2: BFS from non-conflicting direct seeds + overridden assets
        // Overridden assets act as additional seeds so their chosen scope propagates
        // to neighbours the same way a scope-entry match would.
        Map<Long, Set<String>> propagated = new HashMap<>();
        for (Long id : assetIds) propagated.put(id, new HashSet<>());

        for (Map.Entry<Long, String> seed : directStatus.entrySet()) {
            if (INDETERMINATE.equals(seed.getValue())) continue;
            bfs(seed.getKey(), seed.getValue(), assetIds, adj, propagated);
        }

        for (ProjectAssetAccess access : accesses) {
            if (!access.isScopeOverride()) continue;
            String overrideScope = access.getScopeStatus();
            if (INDETERMINATE.equals(overrideScope)) continue;
            bfs(access.getAssetId(), overrideScope, assetIds, adj, propagated);
        }

        // Step 3: compute final status for all non-HOST assets and build effectiveStatus map.
        // HOSTs are skipped here and handled in Step 4 via interface aggregation.
        // Steps 3-5 only ever write into effectiveStatus; status updates are computed once,
        // in a single final diff pass after step 5, so a later step can freely override an
        // earlier step's decision without leaving a stale entry behind.
        Map<Long, ProjectAssetAccess> accessByAsset = accesses.stream()
            .collect(Collectors.toMap(ProjectAssetAccess::getAssetId, a -> a));
        Map<Long, String> effectiveStatus = new HashMap<>();

        for (Long id : assetIds) {
            ProjectAssetAccess access = accessByAsset.get(id);
            if (access == null) continue;

            // Overridden assets keep their status unchanged; record it for step 4.
            if (access.isScopeOverride()) {
                effectiveStatus.put(id, access.getScopeStatus());
                continue;
            }

            // HOSTs are resolved in step 4 — skip persistence here but seed effectiveStatus
            // with the BFS result as a fallback for hosts with no tracked interfaces.
            Asset asset = assetsById.get(id);
            if (asset != null && AssetType.HOST.equals(asset.getType())) {
                String reach = propagated.get(id) == null || propagated.get(id).isEmpty() ? INDETERMINATE
                    : propagated.get(id).size() == 1 ? propagated.get(id).iterator().next()
                    : INDETERMINATE;
                effectiveStatus.put(id, directStatus.getOrDefault(id, reach));
                continue;
            }

            String finalStatus;
            String direct = directStatus.get(id);
            if (direct != null) {
                finalStatus = direct;
            } else {
                Set<String> reach = propagated.get(id);
                finalStatus = (reach == null || reach.isEmpty()) ? INDETERMINATE
                    : (reach.size() == 1) ? reach.iterator().next()
                    : INDETERMINATE;
            }
            effectiveStatus.put(id, finalStatus);
        }

        // Step 4: HOST scope = aggregate of interface scopes.
        //   ALL interfaces in_scope      → host is in_scope
        //   ALL interfaces out_of_scope  → host is out_of_scope
        //   mixed / some indeterminate   → host is indeterminate
        //   no known interfaces          → fall back to BFS result (effectiveStatus)
        //   direct scope entry on host   → direct match wins, no aggregation
        for (Long hostId : assetIds) {
            Asset host = assetsById.get(hostId);
            if (host == null || !AssetType.HOST.equals(host.getType())) continue;
            ProjectAssetAccess access = accessByAsset.get(hostId);
            if (access == null || access.isScopeOverride()) continue;
            if (directStatus.containsKey(hostId)) {
                // Direct scope entry declared for this host; honour it, skip aggregation.
                effectiveStatus.put(hostId, directStatus.get(hostId));
                continue;
            }

            List<Long> ifaceIds = rels.stream()
                .filter(r -> hostId.equals(r.getFromAssetId())
                          && AssetLinkType.HOST_INTERFACE.equals(r.getType())
                          && assetIds.contains(r.getToAssetId()))
                .map(AssetRelationship::getToAssetId)
                .collect(Collectors.toList());

            String hostScope;
            if (ifaceIds.isEmpty()) {
                // No interfaces tracked — fall back to whatever BFS left in effectiveStatus.
                hostScope = effectiveStatus.getOrDefault(hostId, INDETERMINATE);
            } else {
                Set<String> ifaceScopes = ifaceIds.stream()
                    .map(id -> effectiveStatus.getOrDefault(id, INDETERMINATE))
                    .collect(Collectors.toSet());
                hostScope = (ifaceScopes.size() == 1) ? ifaceScopes.iterator().next() : INDETERMINATE;
            }

            effectiveStatus.put(hostId, hostScope);
        }

        // Step 5: third-party KB check — an asset whose own identifier matches an enabled KB
        // entry is reclassified as THIRD_PARTY, prevailing over whatever steps 2-4 computed
        // (in_scope/out_of_scope/indeterminate). Does not apply to overridden assets, and never
        // propagates through the relationship graph or HOST/interface aggregation — only the
        // asset's own identifier is checked directly against KB entries.
        if (!kbEntries.isEmpty()) {
            for (Long id : assetIds) {
                ProjectAssetAccess access = accessByAsset.get(id);
                if (access == null || access.isScopeOverride()) continue;
                Asset asset = assetsById.get(id);
                if (asset == null) continue;
                boolean matchesKb = kbEntries.stream()
                    .anyMatch(e -> matchesByKindAndValue(asset, e.getKind(), e.getValue()));
                if (matchesKb) {
                    effectiveStatus.put(id, THIRD_PARTY);
                }
            }
        }

        // Final pass: diff effectiveStatus against each asset's persisted status to build the
        // set of rows that actually changed. Must run after step 5 so a later step's decision
        // (e.g. third-party overriding an earlier in_scope/out_of_scope result) is never lost.
        Map<Long, String> statusUpdates = new HashMap<>();
        for (Long id : assetIds) {
            ProjectAssetAccess access = accessByAsset.get(id);
            if (access == null || access.isScopeOverride()) continue;
            String newStatus = effectiveStatus.getOrDefault(id, INDETERMINATE);
            if (!newStatus.equals(access.getScopeStatus())) {
                statusUpdates.put(id, newStatus);
            }
        }
        int updated = statusUpdates.size();
        flushStatusUpdates(projectId, statusUpdates);
        return updated;
    }

    /** Returns true if the asset matches any enabled KB third-party entry. */
    public boolean isThirdParty(Asset asset, List<KbThirdPartyEntry> kbEntries) {
        return kbEntries.stream().anyMatch(e -> matchesByKindAndValue(asset, e.getKind(), e.getValue()));
    }

    /**
     * Re-checks every non-overridden asset across ALL projects (every organization) against the
     * current set of enabled KB third-party entries, promoting matches to THIRD_PARTY — this
     * prevails over whatever scope status (in_scope/out_of_scope/indeterminate) the asset
     * currently has. Called whenever a KB third-party entry is added/edited/enabled, since
     * that's a platform-wide pattern change, not scoped to one project.
     *
     * Cheaper than re-running classify() for every project: skips BFS/relationship propagation
     * entirely and only checks each asset's own identifier directly against KB entries — matches
     * never propagate through the relationship graph or HOST/interface aggregation.
     */
    @Transactional
    public int reclassifyThirdPartyPlatformWide() {
        List<KbThirdPartyEntry> kbEntries = kbThirdPartyRepo.findAllByEnabledTrue();
        if (kbEntries.isEmpty()) return 0;

        List<ProjectAssetAccess> candidates = accessRepo.findByScopeOverrideFalse();
        if (candidates.isEmpty()) return 0;

        Set<Long> assetIds = candidates.stream().map(ProjectAssetAccess::getAssetId).collect(Collectors.toSet());
        Map<Long, Asset> assetsById = findAllByIdChunked(assetIds).stream()
            .collect(Collectors.toMap(Asset::getId, a -> a));

        Map<Long, List<Long>> matchedByProject = new HashMap<>();
        for (ProjectAssetAccess access : candidates) {
            if (THIRD_PARTY.equals(access.getScopeStatus())) continue;
            Asset asset = assetsById.get(access.getAssetId());
            if (asset == null) continue;
            boolean matchesKb = kbEntries.stream()
                .anyMatch(e -> matchesByKindAndValue(asset, e.getKind(), e.getValue()));
            if (matchesKb) {
                matchedByProject.computeIfAbsent(access.getProjectId(), k -> new ArrayList<>())
                    .add(access.getAssetId());
            }
        }

        int updated = 0;
        for (Map.Entry<Long, List<Long>> e : matchedByProject.entrySet()) {
            updated += accessRepo.updateScopeStatusForAssets(e.getKey(), e.getValue(), THIRD_PARTY);
        }
        return updated;
    }

    // ── Scope explain ─────────────────────────────────────────────────────────

    /**
     * Explains why an asset has its current scope status in a project.
     * Returns: direct scope-entry matches + immediate neighbours in the propagation
     * graph that could have seeded scope into this asset (one-hop sources).
     */
    public ScopeExplainDto explainScope(Long projectId, Long assetId) {
        List<ProjectScopeEntry> entries = scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId);
        List<ProjectAssetAccess> accesses = accessRepo.findByProjectId(projectId);
        Map<Long, ProjectAssetAccess> accessByAsset = accesses.stream()
            .collect(Collectors.toMap(ProjectAssetAccess::getAssetId, a -> a));

        Set<Long> assetIds = accesses.stream().map(ProjectAssetAccess::getAssetId).collect(Collectors.toSet());
        if (!assetIds.contains(assetId)) throw NotFoundException.of("project_asset_access", assetId);

        Map<Long, Asset> assetsById = findAllByIdChunked(assetIds).stream()
            .collect(Collectors.toMap(Asset::getId, a -> a));

        List<AssetRelationship> rels = relRepo.findByProjectId(projectId);

        Asset target = assetsById.get(assetId);
        ProjectAssetAccess targetAccess = accessByAsset.get(assetId);

        // Direct scope entry matches for this asset
        List<ScopeEntryMatchDto> directMatches = entries.stream()
            .filter(e -> matches(target, e))
            .map(e -> new ScopeEntryMatchDto(e.getId(), e.getKind(), e.getValue(), e.isInScope()))
            .collect(Collectors.toList());

        // One-hop propagation sources: relationships through which scope can arrive at this asset
        List<ScopePropagationSourceDto> sources = new ArrayList<>();
        for (AssetRelationship r : rels) {
            Long from = r.getFromAssetId(), to = r.getToAssetId();
            if (!assetIds.contains(from) || !assetIds.contains(to)) continue;
            String type = r.getType();

            Long sourceId = null;
            String direction = null;
            if (FORWARD_PROPAGATE.contains(type) && to.equals(assetId)) {
                sourceId = from; direction = "forward";
            } else if (REVERSE_PROPAGATE.contains(type) && from.equals(assetId)) {
                sourceId = to; direction = "reverse";
            }
            if (sourceId == null) continue;

            Asset src = assetsById.get(sourceId);
            ProjectAssetAccess srcAccess = accessByAsset.get(sourceId);
            if (src == null || srcAccess == null) continue;

            List<ScopeEntryMatchDto> srcMatches = entries.stream()
                .filter(e -> matches(src, e))
                .map(e -> new ScopeEntryMatchDto(e.getId(), e.getKind(), e.getValue(), e.isInScope()))
                .collect(Collectors.toList());

            sources.add(new ScopePropagationSourceDto(
                sourceId, src.getIdentifier(), src.getType(),
                type, direction,
                srcAccess.getScopeStatus(), srcAccess.isScopeOverride(),
                srcMatches
            ));
        }

        String status = targetAccess != null ? targetAccess.getScopeStatus() : INDETERMINATE;
        boolean override = targetAccess != null && targetAccess.isScopeOverride();
        return new ScopeExplainDto(status, override, directMatches, sources);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Flushes scope-status changes accumulated during classify() as grouped bulk UPDATEs
     * (one per distinct status value, chunked at 30k IDs). Replaces N individual saves
     * to avoid holding the DB connection open for thousands of round-trips.
     */
    private void flushStatusUpdates(Long projectId, Map<Long, String> statusUpdates) {
        if (statusUpdates.isEmpty()) return;
        Map<String, List<Long>> byStatus = new HashMap<>();
        for (Map.Entry<Long, String> e : statusUpdates.entrySet()) {
            byStatus.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        int chunkSize = 30_000;
        for (Map.Entry<String, List<Long>> e : byStatus.entrySet()) {
            List<Long> ids = e.getValue();
            for (int i = 0; i < ids.size(); i += chunkSize) {
                accessRepo.updateScopeStatusForAssets(
                    projectId,
                    ids.subList(i, Math.min(i + chunkSize, ids.size())),
                    e.getKey()
                );
            }
        }
    }

    /**
     * Fetches assets in chunks of 30,000 IDs to stay within PostgreSQL's 65,535
     * prepared-statement parameter limit. Projects with 77k+ assets hit that limit
     * when using the default {@code findAllById} which generates {@code IN (?, ?, …)}.
     */
    private List<Asset> findAllByIdChunked(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Long> idList = (ids instanceof List<Long> l) ? l : new ArrayList<>(ids);
        List<Asset> result = new ArrayList<>(idList.size());
        int chunkSize = 30_000;
        for (int i = 0; i < idList.size(); i += chunkSize) {
            result.addAll(assetRepo.findAllById(idList.subList(i, Math.min(i + chunkSize, idList.size()))));
        }
        return result;
    }

    // ── BFS ───────────────────────────────────────────────────────────────────

    private void bfs(Long seedId, String status, Set<Long> universe,
                     Map<Long, Set<Long>> adj, Map<Long, Set<String>> propagated) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(seedId);
        visited.add(seedId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            propagated.get(cur).add(status);
            for (Long nb : adj.getOrDefault(cur, Set.of())) {
                if (!visited.contains(nb) && universe.contains(nb)) {
                    visited.add(nb);
                    queue.add(nb);
                }
            }
        }
    }

    // ── Direct matching ───────────────────────────────────────────────────────

    private boolean matches(Asset asset, ProjectScopeEntry entry) {
        return matchesByKindAndValue(asset, entry.getKind(), entry.getValue());
    }

    private boolean matchesByKindAndValue(Asset asset, String kind, String val) {
        String id = asset.getIdentifier();
        return switch (kind) {
            case "domain"          -> AssetType.DOMAIN.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "domain_wildcard" -> AssetType.DOMAIN.equals(asset.getType())
                                      && matchesWildcardDomain(id, val);
            case "url"             -> AssetType.WEB_APPLICATION.equals(asset.getType())
                                      && id.equalsIgnoreCase(val);
            case "url_wildcard"    -> AssetType.WEB_APPLICATION.equals(asset.getType())
                                      && matchesUrlWildcard(id, val);
            case "ip"              -> AssetType.IP.equals(asset.getType())
                                      && extractIp(id).equals(val);
            case "cidr"            -> (AssetType.IP.equals(asset.getType())
                                      && AssetService.isIpInCidr(extractIp(id), val))
                                      || (AssetType.NETWORK.equals(asset.getType())
                                      && id.equals(val));
            case "android_app"     -> AssetType.ANDROID_APP.equals(asset.getType())  && id.equals(val);
            case "ios_app"         -> AssetType.IOS_APP.equals(asset.getType())      && id.equals(val);
            case "windows_app"     -> AssetType.WINDOWS_APP.equals(asset.getType())  && id.equals(val);
            case "hardware"        -> AssetType.HARDWARE.equals(asset.getType())      && id.equals(val);
            case "text_contains"   -> AssetType.TEXT_DATA.equals(asset.getType())
                                      && id.toLowerCase().contains(val.toLowerCase());
            default                -> false;
        };
    }

    /** *.example.com matches api.example.com and example.com itself. */
    private boolean matchesWildcardDomain(String domain, String wildcard) {
        String base = wildcard.startsWith("*.") ? wildcard.substring(2) : wildcard;
        return domain.equalsIgnoreCase(base)
            || domain.toLowerCase().endsWith("." + base.toLowerCase());
    }

    /**
     * url_wildcard scope (e.g. https://example.com/* or https://example.com/api/*)
     * matches a WEB_APPLICATION whose identifier starts with the non-wildcard prefix.
     */
    private boolean matchesUrlWildcard(String appIdentifier, String wildcardUrl) {
        String prefix = wildcardUrl.replaceAll("/\\*.*$", "").replaceAll("\\*$", "").stripTrailing();
        return appIdentifier.toLowerCase().startsWith(prefix.toLowerCase());
    }

    /** Extract bare IP from identifiers like "192.168.1.1" or "192.168.1.1:80/tcp". */
    private String extractIp(String id) {
        if (id == null) return "";
        return id.contains(":") ? id.split(":")[0] : id;
    }
}
