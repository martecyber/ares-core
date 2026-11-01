package com.martecyber.ares.assets;

import com.martecyber.ares.assets.dto.AssetNeighborhoodDto;
import com.martecyber.ares.assets.dto.AssetRelationshipDetailDto;
import com.martecyber.ares.assets.dto.AssetRelationshipDto;
import com.martecyber.ares.assets.dto.CreateAssetRelationshipRequest;
import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AssetRelationshipService {

    private final AssetRelationshipRepository repo;
    private final AssetRepository assets;
    private final ProjectRelationshipHiddenRepository hiddenRepo;

    public AssetRelationshipService(AssetRelationshipRepository repo, AssetRepository assets,
                                     ProjectRelationshipHiddenRepository hiddenRepo) {
        this.repo = repo;
        this.assets = assets;
        this.hiddenRepo = hiddenRepo;
    }

    /**
     * Returns all relationships (outgoing + incoming) for the given asset,
     * enriched with the related asset's code, identifier and type.
     */
    public List<AssetRelationshipDetailDto> listAllForAsset(Long assetId) {
        return listAllForAsset(assetId, null);
    }

    /**
     * Same as {@link #listAllForAsset(Long)}, keeping only relationships whose other end the
     * caller can see ({@code visibleOrgIds}; null = unrestricted). A relationship is only shown
     * when the caller can see both assets it joins.
     */
    public List<AssetRelationshipDetailDto> listAllForAsset(Long assetId, Set<Long> visibleOrgIds) {
        assets.findById(assetId).orElseThrow(() -> NotFoundException.of("asset", assetId));

        List<AssetRelationship> outgoing = repo.findByFromAssetId(assetId);
        List<AssetRelationship> incoming = repo.findByToAssetId(assetId);

        // Batch-load related assets to avoid N+1
        Set<Long> relatedIds = new java.util.HashSet<>();
        outgoing.forEach(r -> relatedIds.add(r.getToAssetId()));
        incoming.forEach(r -> relatedIds.add(r.getFromAssetId()));
        Map<Long, Asset> assetMap = assets.findAllById(relatedIds).stream()
            .filter(a -> visibleOrgIds == null || visibleOrgIds.contains(a.getOrganizationId()))
            .collect(Collectors.toMap(Asset::getId, a -> a));

        List<AssetRelationshipDetailDto> result = new ArrayList<>();
        for (AssetRelationship r : outgoing) {
            Asset rel = assetMap.get(r.getToAssetId());
            if (rel == null) continue;
            result.add(new AssetRelationshipDetailDto(
                rel.getId(), rel.getCode(), rel.getIdentifier(), rel.getDisplayName(), rel.getType(),
                r.getType(), "outgoing"));
        }
        for (AssetRelationship r : incoming) {
            Asset rel = assetMap.get(r.getFromAssetId());
            if (rel == null) continue;
            result.add(new AssetRelationshipDetailDto(
                rel.getId(), rel.getCode(), rel.getIdentifier(), rel.getDisplayName(), rel.getType(),
                r.getType(), "incoming"));
        }
        return result;
    }

    /**
     * BFS through the undirected asset graph for an org, starting from fromIds. Returns every
     * connected asset, including the starting assets themselves — this feeds the "affects"
     * picker, and an asset already used as detected_at (i.e. one of fromIds) is a completely
     * valid affects target too (e.g. the same host both detected and affected). Previously this
     * excluded the starting set and filtered results to a hardcoded handful of asset types; both
     * restrictions are gone — any connected asset of any type can be picked as affected.
     */
    public List<Asset> findReachable(Long orgId, List<Long> fromIds) {
        if (fromIds == null || fromIds.isEmpty()) return List.of();

        // Load all edges for the org and build an undirected adjacency map
        List<AssetRelationship> allRels = repo.findByOrganizationId(orgId);
        Map<Long, Set<Long>> adj = new HashMap<>();
        for (AssetRelationship r : allRels) {
            adj.computeIfAbsent(r.getFromAssetId(), k -> new HashSet<>()).add(r.getToAssetId());
            adj.computeIfAbsent(r.getToAssetId(), k -> new HashSet<>()).add(r.getFromAssetId());
        }

        // BFS
        Set<Long> visited = new HashSet<>(fromIds);
        Queue<Long> queue = new LinkedList<>(fromIds);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long nb : adj.getOrDefault(cur, Set.of())) {
                if (visited.add(nb)) queue.offer(nb);
            }
        }

        if (visited.isEmpty()) return List.of();
        // fromIds are caller-supplied: drop anything outside the org instead of echoing it back.
        return assets.findAllById(visited).stream()
            .filter(a -> orgId.equals(a.getOrganizationId()))
            .toList();
    }

    /** Hard cap on visited nodes — protects the DB and the JVM from runaway BFS on densely-linked orgs. */
    private static final int NEIGHBORHOOD_NODE_CAP = 1500;

    /**
     * BFS through the undirected asset graph, bounded to {@code maxHops} hops from a single
     * focus asset (both incoming and outgoing edges are followed). Returns the visited assets
     * (including the focus asset itself) plus the relationships connecting them, so a graph
     * view can render the neighborhood in isolation.
     *
     * Queries are scoped to the current frontier at each hop (not the org's entire
     * relationship graph) — for a hop-limited radius around one asset, this stays fast
     * even in orgs with a huge overall relationship graph (e.g. from subdomain enumeration).
     */
    public AssetNeighborhoodDto findNeighborhood(Long orgId, Long centerAssetId, int maxHops) {
        int hops = Math.max(1, Math.min(maxHops, 10));

        Set<Long> visited = new HashSet<>(List.of(centerAssetId));
        List<AssetRelationship> collectedRels = new ArrayList<>();
        Set<Long> frontier = new HashSet<>(List.of(centerAssetId));

        for (int depth = 0; depth < hops && !frontier.isEmpty() && visited.size() < NEIGHBORHOOD_NODE_CAP; depth++) {
            List<AssetRelationship> levelRels = repo.findByFromAssetIdInOrToAssetIdIn(frontier, frontier);
            Set<Long> next = new HashSet<>();
            for (AssetRelationship r : levelRels) {
                collectedRels.add(r);
                Long other = frontier.contains(r.getFromAssetId()) ? r.getToAssetId() : r.getFromAssetId();
                if (visited.size() >= NEIGHBORHOOD_NODE_CAP) break;
                if (visited.add(other)) next.add(other);
            }
            frontier = next;
        }

        List<Asset> neighborhoodAssets = assets.findAllById(visited).stream()
            .filter(a -> a.getOrganizationId().equals(orgId)) // defensive: ignore cross-org leakage
            .toList();
        Set<Long> assetIds = neighborhoodAssets.stream().map(Asset::getId).collect(Collectors.toSet());
        List<AssetRelationshipDto> neighborhoodRels = collectedRels.stream()
            .distinct()
            .filter(r -> assetIds.contains(r.getFromAssetId()) && assetIds.contains(r.getToAssetId()))
            .map(AssetRelationshipDto::from)
            .toList();

        return new AssetNeighborhoodDto(neighborhoodAssets, neighborhoodRels);
    }

    public List<AssetRelationshipDto> listByOrganization(Long organizationId) {
        return listByOrganization(organizationId, null);
    }

    /** Org relationships the caller can see both ends of ({@code visibleOrgIds}; null = unrestricted). */
    public List<AssetRelationshipDto> listByOrganization(Long organizationId, Set<Long> visibleOrgIds) {
        return onlyBetweenVisible(repo.findByOrganizationId(organizationId), visibleOrgIds).stream()
            .map(AssetRelationshipDto::from).toList();
    }

    public List<AssetRelationshipDto> listByProject(Long projectId) {
        return listByProject(projectId, null);
    }

    /** Project relationships the caller can see both ends of ({@code visibleOrgIds}; null = unrestricted). */
    public List<AssetRelationshipDto> listByProject(Long projectId, Set<Long> visibleOrgIds) {
        return onlyBetweenVisible(repo.findByProjectId(projectId), visibleOrgIds).stream()
            .map(AssetRelationshipDto::from).toList();
    }

    /** Drops every relationship with an end outside {@code visibleOrgIds} (null = keep all). */
    List<AssetRelationship> onlyBetweenVisible(List<AssetRelationship> rels, Set<Long> visibleOrgIds) {
        if (visibleOrgIds == null || rels.isEmpty()) return rels;
        if (visibleOrgIds.isEmpty()) return List.of();
        Set<Long> ends = new HashSet<>();
        rels.forEach(r -> { ends.add(r.getFromAssetId()); ends.add(r.getToAssetId()); });
        Set<Long> visible = new HashSet<>(assets.findIdsInOrganizations(ends, visibleOrgIds));
        return rels.stream()
            .filter(r -> visible.contains(r.getFromAssetId()) && visible.contains(r.getToAssetId()))
            .toList();
    }

    public List<AssetRelationshipDto> listFrom(Long assetId) {
        assets.findById(assetId).orElseThrow(() -> NotFoundException.of("asset", assetId));
        return repo.findByFromAssetId(assetId).stream().map(AssetRelationshipDto::from).toList();
    }

    @Transactional
    public AssetRelationshipDto create(Long fromAssetId, CreateAssetRelationshipRequest req) {
        Asset from = assets.findById(fromAssetId).orElseThrow(() -> NotFoundException.of("asset", fromAssetId));
        Asset to   = assets.findById(req.toAssetId()).orElseThrow(() -> NotFoundException.of("asset", req.toAssetId()));

        if (fromAssetId.equals(req.toAssetId())) {
            throw new IllegalArgumentException("An asset cannot be linked to itself");
        }
        if (!java.util.Objects.equals(from.getOrganizationId(), to.getOrganizationId())) {
            throw new IllegalArgumentException("Both assets must belong to the same organization");
        }
        if (AssetLinkType.AUTO_COMPUTED.contains(req.linkType())) {
            throw new IllegalArgumentException(
                "Link type '" + req.linkType() + "' is computed automatically and cannot be created manually");
        }
        AssetLinkType.validate(from.getType(), req.linkType(), to.getType());

        var existing = repo.findById(new AssetRelationshipId(fromAssetId, req.toAssetId(), req.linkType()));
        if (existing.isPresent()) return AssetRelationshipDto.from(existing.get());

        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(fromAssetId);
        r.setToAssetId(req.toAssetId());
        r.setType(req.linkType());
        r.setCreatedAt(OffsetDateTime.now());
        return AssetRelationshipDto.from(repo.save(r));
    }

    /** The full link matrix, for clients building a "create relationship" form. */
    public List<com.martecyber.ares.assets.dto.AssetLinkTypeDto> linkTypes() {
        List<com.martecyber.ares.assets.dto.AssetLinkTypeDto> out = new java.util.ArrayList<>();
        AssetLinkType.matrix().forEach((fromType, byLink) -> byLink.forEach((linkType, toTypes) ->
            out.add(new com.martecyber.ares.assets.dto.AssetLinkTypeDto(
                linkType, fromType, toTypes.stream().sorted().toList(),
                !AssetLinkType.AUTO_COMPUTED.contains(linkType)))));
        out.sort(java.util.Comparator.comparing(com.martecyber.ares.assets.dto.AssetLinkTypeDto::fromType)
            .thenComparing(com.martecyber.ares.assets.dto.AssetLinkTypeDto::linkType));
        return out;
    }

    /** Idempotent link creation used by importers (e.g. network auto-linking). Validates the
     *  (fromType, linkType, toType) triple, same as the manual {@link #create} path. */
    @Transactional
    public void linkIfAbsent(Long fromAssetId, Long toAssetId, String linkType) {
        // Primary-key lookup first (was: load every outgoing link of the asset and scan them).
        // An existing row was validated when created, so neither asset needs loading.
        if (repo.existsById(new AssetRelationshipId(fromAssetId, toAssetId, linkType))) return;
        Asset from = assets.findById(fromAssetId)
            .orElseThrow(() -> NotFoundException.of("asset", fromAssetId));
        Asset to = assets.findById(toAssetId)
            .orElseThrow(() -> NotFoundException.of("asset", toAssetId));
        AssetLinkType.validate(from.getType(), linkType, to.getType());
        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(fromAssetId);
        r.setToAssetId(toAssetId);
        r.setType(linkType);
        r.setCreatedAt(OffsetDateTime.now());
        repo.save(r);
    }

    @Transactional
    public void delete(Long fromAssetId, Long toAssetId, String type) {
        repo.deleteByFromAssetIdAndToAssetIdAndType(fromAssetId, toAssetId, type);
    }

    /** Hides a relationship from a single project's graph view — the underlying (org-wide)
     *  relationship row is left untouched, unlike {@link #delete}. */
    @Transactional
    public void hideForProject(Long projectId, Long fromAssetId, Long toAssetId, String type) {
        hiddenRepo.hideIfAbsent(projectId, fromAssetId, toAssetId, type);
    }
}
