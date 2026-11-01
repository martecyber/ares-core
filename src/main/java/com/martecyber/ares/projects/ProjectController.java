package com.martecyber.ares.projects;

import com.martecyber.ares.assets.AssetRelationshipService;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.imports.AssetImportHelper;
import com.martecyber.ares.imports.ParsedAsset;
import com.martecyber.ares.projects.dto.*;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService service;
    private final ProjectAssetAccessService assetAccessSvc;
    private final ScopeEntryAssetDeriver deriver;
    private final AssetScopeClassifier classifier;
    private final ScopeClassifyScheduler scheduler;
    private final AssetImportHelper assetHelper;
    private final AssetRepository assetRepo;
    private final ProjectRepository projectRepo;
    private final MonitorStatsService monitorStats;
    private final AssetRelationshipService relSvc;
    private final OrgScopeService orgScope;

    public ProjectController(ProjectService service, ProjectAssetAccessService assetAccessSvc,
                             ScopeEntryAssetDeriver deriver, AssetScopeClassifier classifier,
                             ScopeClassifyScheduler scheduler,
                             AssetImportHelper assetHelper, AssetRepository assetRepo,
                             ProjectRepository projectRepo, MonitorStatsService monitorStats,
                             AssetRelationshipService relSvc, OrgScopeService orgScope) {
        this.orgScope = orgScope;
        this.service = service;
        this.assetAccessSvc = assetAccessSvc;
        this.deriver = deriver;
        this.classifier = classifier;
        this.scheduler = scheduler;
        this.assetHelper = assetHelper;
        this.assetRepo = assetRepo;
        this.relSvc = relSvc;
        this.projectRepo = projectRepo;
        this.monitorStats = monitorStats;
    }

    /**
     * MSSP_OPERATOR is restricted to its assigned organizations, so the role check in
     * {@code @PreAuthorize} is not enough on its own — every project-scoped endpoint an
     * operator can reach must also check the project's organization. (MSSP_ADMIN-only
     * endpoints don't need it: the platform admin is unrestricted.)
     */
    private void checkProject(Long projectId) {
        orgScope.assertProjectAccess(SecurityContextHolder.getContext().getAuthentication(), projectId);
    }

    public record ManualAssetRequest(String type, String identifier, Map<String, Object> metadata, String hostSubtype,
                                      List<String> hostnames, String displayName) {}

    @GetMapping("/schedule")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ProjectDto> schedule(
        @RequestParam String start,
        @RequestParam String end,
        Authentication auth
    ) {
        java.util.Set<Long> orgs = orgScope.accessibleOrgIds(auth); // null = unrestricted
        return service.schedule(LocalDate.parse(start), LocalDate.parse(end)).stream()
            .filter(p -> orgs == null || orgs.contains(p.organizationId()))
            .toList();
    }

    @GetMapping("/preview-code")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, String> previewCode(
        @RequestParam Long organizationId,
        @RequestParam(required = false) Long typeId,
        Authentication auth
    ) {
        orgScope.assertOrgAccess(auth, organizationId);
        return Map.of("code", service.previewCode(organizationId, typeId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<ProjectDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        Authentication auth
    ) {
        return PagedResponse.of(service.list(organizationId, status, page, size, auth), e -> e);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public ProjectDto get(@PathVariable Long id, Authentication auth) {
        return service.get(id, auth);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<ProjectDto> create(@Valid @RequestBody CreateProjectRequest req, Authentication auth) {
        orgScope.assertOrgAccess(auth, req.organizationId());
        ProjectDto created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ProjectDto update(@PathVariable Long id, @Valid @RequestBody UpdateProjectRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ProjectDto complete(@PathVariable Long id) {
        checkProject(id);
        return service.complete(id);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ProjectDto reopen(@PathVariable Long id) {
        return service.reopen(id);
    }

    @PostMapping("/{id}/approve-iteration")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ProjectDto approveIteration(@PathVariable Long id) {
        checkProject(id);
        return service.approveIteration(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scope")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<List<ScopeEntryDto>> addScope(@PathVariable Long id, @Valid @RequestBody AddScopeEntryRequest req) {
        checkProject(id);
        List<ScopeEntryDto> entries = service.addScopeEntries(id, req);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(entries);
    }

    @DeleteMapping("/{id}/scope/{entryId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> removeScope(@PathVariable Long id, @PathVariable Long entryId) {
        checkProject(id);
        service.removeScopeEntry(id, entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/scope/derive")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Integer> deriveAssetsFromScope(@PathVariable Long id) {
        checkProject(id);
        ScopeEntryAssetDeriver.DeriveResult result = deriver.deriveAll(id);
        return Map.of("derived", result.derived(), "skipped", result.skipped());
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<ProjectDto.MemberDto> addMember(@PathVariable Long id, @Valid @RequestBody AddMemberRequest req) {
        ProjectDto.MemberDto member = service.addMember(id, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long userId,
                                             @org.springframework.web.bind.annotation.RequestParam String role) {
        service.removeMember(id, userId, role);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/my-role")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public java.util.Map<String, String> getMyRole(@PathVariable Long id) {
        checkProject(id);
        String role = service.getMyProjectRole(id);
        return java.util.Map.of("role", role != null ? role : "");
    }

    @GetMapping("/{id}/assets")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<Long> listAssets(@PathVariable Long id) {
        checkProject(id);
        return assetAccessSvc.listAssetIds(id);
    }

    @PostMapping("/{id}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void addAsset(@PathVariable Long id, @PathVariable Long assetId) {
        checkProject(id);
        assetAccessSvc.add(id, assetId);
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void removeAsset(@PathVariable Long id, @PathVariable Long assetId) {
        checkProject(id);
        assetAccessSvc.remove(id, assetId);
    }

    /** Hides a relationship from this project's graph — the relationship itself (org-wide)
     *  is untouched, unlike {@code DELETE /assets/{id}/relationships} which is a hard delete. */
    @DeleteMapping("/{id}/relationships")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void hideRelationship(@PathVariable Long id,
                                  @RequestParam Long fromAssetId,
                                  @RequestParam Long toAssetId,
                                  @RequestParam String linkType) {
        checkProject(id);
        relSvc.hideForProject(id, fromAssetId, toAssetId, linkType);
    }

    /**
     * Manually add an asset to a project by type+identifier.
     * If an asset with that (org, type, identifier) already exists it is reused;
     * otherwise a new asset is created. Either way the asset is linked to the project.
     * Returns the asset data plus a `created` flag indicating whether a new asset was made.
     */
    @PostMapping("/{id}/assets/manual")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Object> addAssetManually(@PathVariable Long id,
                                                 @RequestBody ManualAssetRequest req) {
        checkProject(id);
        Project project = projectRepo.findById(id)
            .orElseThrow(() -> com.martecyber.ares.common.NotFoundException.of("project", id));

        // Normalized first, so "Example.COM " finds the existing example.com instead of
        // creating a near-duplicate that later imports would never match.
        String identifier = com.martecyber.ares.assets.AssetIdentifierRules.normalize(req.type(), req.identifier());

        boolean existed = assetRepo.findByOrganizationIdAndTypeAndIdentifier(
            project.getOrganizationId(), req.type(), identifier).isPresent();

        Long assetId = assetHelper.resolveOrCreate(project.getOrganizationId(),
            new ParsedAsset(identifier, req.type(), req.metadata() != null ? req.metadata() : Map.of()));

        assetAccessSvc.add(id, assetId);

        com.martecyber.ares.assets.Asset asset = assetRepo.findById(assetId)
            .orElseThrow(() -> com.martecyber.ares.common.NotFoundException.of("asset", assetId));

        // Display name only on actual creation — reusing an existing asset must not rename it.
        String displayName = com.martecyber.ares.assets.AssetIdentifierRules.normalizeDisplayName(req.displayName());
        if (!existed && displayName != null) {
            asset.setDisplayName(displayName);
            assetRepo.save(asset);
        }

        // Only set on actual creation — reusing an existing host must not overwrite its subtype
        // or pin a Name it didn't ask to pin.
        if (!existed && com.martecyber.ares.assets.AssetType.HOST.equals(req.type())) {
            String subtype = req.hostSubtype();
            asset.setHostSubtype(com.martecyber.ares.assets.HostSubtype.ALL.contains(subtype)
                ? subtype : com.martecyber.ares.assets.HostSubtype.UNKNOWN);
            if (req.hostnames() != null && !req.hostnames().isEmpty()) {
                asset.setHostnames(req.hostnames());
            }
            // A manually-created host always has an explicit, required identifier — pin it,
            // same as directly editing Name does (see AssetService.create/update).
            asset.setNameOverride(true);
            assetRepo.save(asset);
        }

        return Map.of(
            "id",         asset.getId(),
            "code",       asset.getCode() != null ? asset.getCode() : "",
            "type",       asset.getType(),
            "identifier", asset.getIdentifier(),
            "created",    !existed
        );
    }

    // ── Scope classification ───────────────────────────────────────────────

    /** Schedules an async scope reclassification. Returns 202 immediately. */
    @PostMapping("/{id}/classify-scope")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Map<String, Boolean>> classifyScope(@PathVariable Long id) {
        checkProject(id);
        scheduler.schedule(id);
        return ResponseEntity.accepted().body(Map.of("scheduled", true));
    }

    /** Polls whether a scope classification is currently running for this project. */
    @GetMapping("/{id}/classify-scope/status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Boolean> classifyScopeStatus(@PathVariable Long id) {
        checkProject(id);
        return Map.of("running", scheduler.isClassifying(id));
    }

    /** Returns scope status for every asset in the project (assetId → {status, override}). */
    @GetMapping("/{id}/scope-status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<Long, ScopeStatusDto> getScopeStatuses(@PathVariable Long id) {
        checkProject(id);
        return assetAccessSvc.getScopeStatuses(id);
    }

    /** Manually override the scope status for one asset. Sets override=true. */
    @PatchMapping("/{id}/scope-status/{assetId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ScopeStatusDto setOverride(
        @PathVariable Long id,
        @PathVariable Long assetId,
        @RequestBody Map<String, String> body
    ) {
        checkProject(id);
        String status = body.get("status");
        if (!java.util.Set.of(AssetScopeClassifier.IN_SCOPE, AssetScopeClassifier.OUT_OF_SCOPE, AssetScopeClassifier.INDETERMINATE).contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
        return assetAccessSvc.setOverride(id, assetId, status);
    }

    /** Clears manual override — the classifier may update this asset on next run. */
    @DeleteMapping("/{id}/scope-status/{assetId}/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void clearOverride(@PathVariable Long id, @PathVariable Long assetId) {
        checkProject(id);
        assetAccessSvc.clearOverride(id, assetId);
    }

    /** Explains why an asset has its current scope status (direct matches + propagation sources). */
    @GetMapping("/{id}/assets/{assetId}/scope-explain")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public com.martecyber.ares.projects.dto.ScopeExplainDto explainScope(
        @PathVariable Long id, @PathVariable Long assetId) {
        checkProject(id);
        return classifier.explainScope(id, assetId);
    }

    // ── Iterations ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/iterations")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ProjectService.ProjectIterationDto> listIterations(@PathVariable Long id) {
        checkProject(id);
        return service.listIterations(id);
    }

    @PatchMapping("/{id}/iterations/{label}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ProjectService.ProjectIterationDto updateIteration(
        @PathVariable Long id,
        @PathVariable String label,
        @RequestBody Map<String, String> body
    ) {
        java.time.LocalDate actualStart = body.containsKey("actualStart") && body.get("actualStart") != null
            ? java.time.LocalDate.parse(body.get("actualStart")) : null;
        java.time.LocalDate actualEnd = body.containsKey("actualEnd") && body.get("actualEnd") != null
            ? java.time.LocalDate.parse(body.get("actualEnd")) : null;
        return service.updateIteration(id, label, actualStart, actualEnd);
    }

    // ── MONITOR stats ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/monitor-stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public MonitorStatsService.MonitorStats monitorStats(@PathVariable Long id) {
        checkProject(id);
        return monitorStats.compute(id);
    }
}
