package com.martecyber.ares.assets;

import com.martecyber.ares.assets.dto.AssetNeighborhoodDto;
import com.martecyber.ares.assets.dto.AssetRelationshipDetailDto;
import com.martecyber.ares.assets.dto.AssetRelationshipDto;
import com.martecyber.ares.assets.dto.CreateAssetRelationshipRequest;
import com.martecyber.ares.assets.dto.CreateWebEndpointHttpSampleRequest;
import com.martecyber.ares.assets.dto.WebEndpointHttpSampleDto;
import com.martecyber.ares.common.PagedResponse;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService service;
    private final AssetRelationshipService relSvc;
    private final WebEndpointHttpSampleService sampleSvc;
    private final AssetMergeService mergeSvc;
    private final ServiceVisibilityRepository visibilityRepo;
    private final AssetToolSightingService sightingSvc;
    private final com.martecyber.ares.users.OrgScopeService orgScope;
    private final com.martecyber.ares.projects.ScopeClassifyScheduler scopeScheduler;

    public AssetController(AssetService service, AssetRelationshipService relSvc,
                           WebEndpointHttpSampleService sampleSvc, AssetMergeService mergeSvc,
                           ServiceVisibilityRepository visibilityRepo,
                           AssetToolSightingService sightingSvc,
                           com.martecyber.ares.users.OrgScopeService orgScope,
                           @org.springframework.context.annotation.Lazy
                           com.martecyber.ares.projects.ScopeClassifyScheduler scopeScheduler) {
        this.scopeScheduler = scopeScheduler;
        this.service        = service;
        this.relSvc         = relSvc;
        this.sampleSvc      = sampleSvc;
        this.mergeSvc       = mergeSvc;
        this.visibilityRepo = visibilityRepo;
        this.sightingSvc    = sightingSvc;
        this.orgScope       = orgScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<Asset> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> scopeStatus,
        @RequestParam(required = false) String protocol,
        @RequestParam(required = false) List<String> types,
        @RequestParam(required = false) List<String> hostSubtypes,
        @RequestParam(required = false) List<String> visibility,
        @RequestParam(required = false) String aql,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        // aql present -> discrete filter params ignored, same coexistence rule as Detection's/Finding's.
        if (aql != null && !aql.isBlank()) {
            return PagedResponse.of(
                service.listByAql(organizationId, projectId, aql, sortBy, sortDir, page, size), a -> a);
        }
        return PagedResponse.of(
            service.list(organizationId, projectId, type, q, scopeStatus, protocol,
                types, hostSubtypes, visibility, sortBy, sortDir, page, size),
            a -> a);
    }

    @GetMapping("/type-counts")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public java.util.Map<String, Long> typeCounts(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId
    ) {
        return service.countByType(organizationId, projectId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Asset get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Asset> create(@Valid @RequestBody CreateAssetRequest req) {
        Asset created = service.create(req.organizationId(), req.code(), req.type(), req.identifier(),
            req.metadata(), req.hostSubtype(), req.hostnames(), req.displayName());
        return ResponseEntity.created(URI.create("/api/v1/assets/" + created.getId())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Asset update(@PathVariable Long id, @RequestBody UpdateAssetRequest req) {
        return service.update(id, req.type(), req.identifier(), req.metadata(), req.hostSubtype(),
            req.hostnames(), req.nameOverride(), req.displayName());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bulk")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> bulkDelete(@RequestBody BulkIdsRequest req) {
        if (req.ids() == null || req.ids().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids must not be empty");
        service.bulkDelete(req.ids());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk/orphan-preview")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public List<AssetOrphanInfo> orphanPreview(@RequestBody BulkIdsRequest req) {
        return service.previewOrphans(req.ids()).stream()
            .map(row -> new AssetOrphanInfo(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                (String) row[3]
            ))
            .toList();
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Asset merge(@PathVariable Long id, @RequestBody AssetMergeService.MergeRequest req) {
        service.assertAccess(id); // the target must share the source's org (enforced in merge)
        return mergeSvc.merge(id, req);
    }

    @GetMapping("/reachable")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<Asset> findReachable(
        @RequestParam Long orgId,
        @RequestParam List<Long> fromIds,
        org.springframework.security.core.Authentication auth
    ) {
        orgScope.assertOrgAccess(auth, orgId);
        return relSvc.findReachable(orgId, fromIds);
    }

    /** Assets + relationships within {@code maxHops} of a focus asset — for graph-view pickers. */
    @GetMapping("/neighborhood")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public AssetNeighborhoodDto neighborhood(
        @RequestParam Long orgId,
        @RequestParam Long assetId,
        @RequestParam(defaultValue = "4") int maxHops,
        org.springframework.security.core.Authentication auth
    ) {
        orgScope.assertOrgAccess(auth, orgId);
        return relSvc.findNeighborhood(orgId, assetId, maxHops);
    }

    // ── Relationships ─────────────────────────────────────────────────────
    // Reads are open to clients too: a caller sees a relationship only when it can see both
    // assets it joins (both ends in an accessible organization).

    @GetMapping("/relationships")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AssetRelationshipDto> listRelationships(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId,
        org.springframework.security.core.Authentication auth
    ) {
        if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
            return relSvc.listByProject(projectId, orgScope.accessibleOrgIds(auth));
        }
        if (organizationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId or projectId is required");
        }
        orgScope.assertOrgAccess(auth, organizationId);
        return relSvc.listByOrganization(organizationId, orgScope.accessibleOrgIds(auth));
    }

    @GetMapping("/{id}/relationships")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AssetRelationshipDetailDto> listRelationships(@PathVariable Long id) {
        service.assertAccess(id);
        return relSvc.listAllForAsset(id, orgScope.currentAccessibleOrgIds());
    }

    @GetMapping("/link-types")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<com.martecyber.ares.assets.dto.AssetLinkTypeDto> linkTypes() {
        return relSvc.linkTypes();
    }

    @PostMapping("/{id}/relationships")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AssetRelationshipDto addRelationship(
        @PathVariable Long id,
        @Valid @RequestBody CreateAssetRelationshipRequest req
    ) {
        service.get(id); // org access check; the target must share the org (enforced in create)
        AssetRelationshipDto dto = relSvc.create(id, req);
        scopeScheduler.scheduleForAsset(id);
        return dto;
    }

    @DeleteMapping("/{id}/relationships")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void removeRelationship(
        @PathVariable Long id,
        @RequestParam Long toAssetId,
        @RequestParam String linkType
    ) {
        service.get(id);
        relSvc.delete(id, toAssetId, linkType);
        scopeScheduler.scheduleForAsset(id);
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Asset assignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return service.assignTag(id, tagId);
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Asset unassignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return service.unassignTag(id, tagId);
    }

    // ── Service visibility (service-type assets only) ─────────────────────

    @GetMapping("/{id}/visibility")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ServiceVisibilityDto> listVisibility(@PathVariable Long id) {
        Asset asset = service.get(id);
        if (!AssetType.SERVICE.equals(asset.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Visibility tracking is only available for SERVICE assets");
        }
        return visibilityRepo.findByServiceAssetIdOrderByLastSeenDesc(id).stream()
            .map(ServiceVisibilityDto::from).toList();
    }

    /**
     * Bulk aggregate visibility lookup for table rendering. Returns a map of
     * (assetId → "OPEN" | "FILTERED" | "CLOSED"). Asset IDs with no visibility
     * records are simply absent from the response.
     */
    @GetMapping("/visibility-aggregates")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<Long, String> visibilityAggregates(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        service.assertAccess(ids);
        Map<Long, String> out = new HashMap<>();
        for (Object[] row : visibilityRepo.aggregateByAssetIds(ids)) {
            out.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return out;
    }

    /**
     * Bulk lookup: for each SERVICE asset id, return the owning HOST asset
     * (service → interface → host chain). Services without a complete chain are
     * absent from the response.
     */
    @GetMapping("/service-hosts")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<Long, ServiceHostDto> serviceHosts(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        service.assertAccess(ids);
        Map<Long, ServiceHostDto> out = new HashMap<>();
        for (Object[] row : service.findHostsForServices(ids)) {
            Long serviceId = ((Number) row[0]).longValue();
            Long hostId    = ((Number) row[1]).longValue();
            out.put(serviceId, new ServiceHostDto(hostId, (String) row[2], (String) row[3]));
        }
        return out;
    }

    // ── Tool sightings (all asset types) ─────────────────────────────────

    /** Project-context asset detail page only — see {@link AssetToolSighting#getProjectId()}'s
     *  own doc comment for why a project id is required here (never optional): the same asset can
     *  be linked to several different projects, and tool-level discovery provenance is exclusive
     *  to whichever project actually produced it. */
    @GetMapping("/{id}/tool-sightings")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AssetToolSightingService.AssetToolSightingDto> listToolSightings(
            @PathVariable Long id, @RequestParam Long projectId, org.springframework.security.core.Authentication auth) {
        orgScope.assertProjectAccess(auth, projectId);
        return sightingSvc.listByAssetAndProject(id, projectId);
    }

    /** The source tools' own ids for this asset (Tenable asset.id, CrowdStrike device_id, …) —
     *  staff-only, like tool sightings, since it reveals which tools the MSSP runs. */
    @GetMapping("/{id}/external-ids")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AssetToolSightingService.ExternalIdDto> listExternalIds(@PathVariable Long id) {
        service.assertAccess(id);
        return sightingSvc.listExternalIds(id);
    }

    /** Organization-context asset detail page — which PROJECTS have discovered this asset, never
     *  which tool each one used (see {@link AssetToolSightingService#listProjectsForAsset}). */
    @GetMapping("/{id}/tool-sighting-projects")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AssetToolSightingService.ProjectSightingDto> listToolSightingProjects(
            @PathVariable Long id, @RequestParam Long organizationId, org.springframework.security.core.Authentication auth) {
        orgScope.assertOrgAccess(auth, organizationId);
        return sightingSvc.listProjectsForAsset(id, organizationId);
    }

    // ── HTTP Samples (web_endpoint only) ──────────────────────────────────

    @GetMapping("/{id}/http-samples")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<WebEndpointHttpSampleDto> listHttpSamples(@PathVariable Long id) {
        service.assertAccess(id);
        return sampleSvc.listByEndpoint(id);
    }

    @PostMapping("/{id}/http-samples")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WebEndpointHttpSampleDto createHttpSample(
        @PathVariable Long id,
        @RequestBody CreateWebEndpointHttpSampleRequest req
    ) {
        service.assertAccess(id);
        return sampleSvc.create(id, req);
    }

    @PatchMapping("/{id}/http-samples/{sampleId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WebEndpointHttpSampleDto updateHttpSample(
        @PathVariable Long id,
        @PathVariable Long sampleId,
        @RequestBody CreateWebEndpointHttpSampleRequest req
    ) {
        service.assertAccess(id); // the sample must belong to this asset (enforced in update)
        return sampleSvc.update(id, sampleId, req);
    }

    @DeleteMapping("/{id}/http-samples/{sampleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void deleteHttpSample(@PathVariable Long id, @PathVariable Long sampleId) {
        service.assertAccess(id);
        sampleSvc.delete(id, sampleId);
    }

    // ── Request records ───────────────────────────────────────────────────

    record CreateAssetRequest(
        @NotNull Long organizationId,
        String code,             // optional — auto-generated from type + identifier if absent
        @NotBlank String type,
        @NotBlank String identifier,
        String metadata,
        String hostSubtype,       // optional — only meaningful when type == "host"; defaults to "unknown"
        List<String> hostnames,   // optional — only meaningful when type == "host"
        String displayName        // optional user label, any type
    ) {}

    record UpdateAssetRequest(
        String type, String identifier, String metadata, String hostSubtype,
        List<String> hostnames,  // optional — only meaningful when type == "host"; full-list replace
        Boolean nameOverride,    // optional — only meaningful when type == "host"; null = don't touch
        String displayName       // optional user label, any type; null = don't touch, "" = clear
    ) {}

    record BulkIdsRequest(List<Long> ids) {}
    record AssetOrphanInfo(Long id, String identifier, String type, String code) {}
}
