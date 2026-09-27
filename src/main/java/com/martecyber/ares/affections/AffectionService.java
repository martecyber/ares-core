package com.martecyber.ares.affections;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AffectionService {

    private final AffectionRepository repo;
    private final AffectionAssetRepository assetLinkRepo;
    private final AffectionAffectsLinkRepository affectsLinkRepo;
    private final AffectStatusHistoryRepository historyRepo;
    private final FindingRepository findingRepo;
    private final DetectionRepository detectionRepo;
    private final AssetRepository assetRepo;
    private final UserRepository userRepo;
    private final FindingService findingService;
    private final OrgScopeService orgScope;

    public AffectionService(AffectionRepository repo,
                            AffectionAssetRepository assetLinkRepo,
                            AffectionAffectsLinkRepository affectsLinkRepo,
                            AffectStatusHistoryRepository historyRepo,
                            FindingRepository findingRepo,
                            DetectionRepository detectionRepo,
                            AssetRepository assetRepo,
                            UserRepository userRepo,
                            @Lazy FindingService findingService,
                            OrgScopeService orgScope) {
        this.repo           = repo;
        this.assetLinkRepo  = assetLinkRepo;
        this.affectsLinkRepo = affectsLinkRepo;
        this.historyRepo    = historyRepo;
        this.findingRepo    = findingRepo;
        this.detectionRepo  = detectionRepo;
        this.assetRepo      = assetRepo;
        this.userRepo       = userRepo;
        this.findingService = findingService;
        this.orgScope       = orgScope;
    }

    // ── Affection metadata ────────────────────────────────────────────────────────

    @Transactional
    public FindingDto update(Long id, String title, String description) {
        Affection a = getEntity(id);
        if (title != null) a.setTitle(title.isBlank() ? null : title.trim());
        if (description != null) a.setDescription(description.isBlank() ? null : description);
        a.setUpdatedAt(OffsetDateTime.now());
        repo.save(a);
        return findingService.get(a.getFindingId());
    }

    @Transactional
    public FindingDto create(Long findingId, String title, String description) {
        com.martecyber.ares.findings.Finding f = findingRepo.findById(findingId)
            .orElseThrow(() -> NotFoundException.of("finding", findingId));
        orgScope.assertProjectAccess(SecurityContextHolder.getContext().getAuthentication(), f.getProjectId());
        Affection a = new Affection();
        a.setFindingId(findingId);
        a.setCode(findingService.generateAffectionCode(findingId,
            f.getCode() != null ? f.getCode() : "F" + findingId));
        if (title != null && !title.isBlank()) a.setTitle(title.trim());
        if (description != null && !description.isBlank()) a.setDescription(description);
        OffsetDateTime now = OffsetDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        repo.save(a);
        return findingService.get(findingId);
    }

    // ── Detected-at asset links ───────────────────────────────────────────────────

    @Transactional
    public FindingDto addDetectedAt(Long affectionId, Long assetId) {
        Affection a = getEntity(affectionId);
        assetRepo.findById(assetId).orElseThrow(() -> NotFoundException.of("asset", assetId));
        a.addAssetLink(assetId, "detected_at", OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        repo.save(a);
        return findingService.get(a.getFindingId());
    }

    @Transactional
    public FindingDto removeDetectedAt(Long affectionId, Long assetId) {
        Affection a = getEntity(affectionId);
        Long findingId = a.getFindingId();
        // Drop affects links anchored on this detected_at first; some affects may
        // become orphaned (no remaining link from any detected_at) and need their
        // affection_asset role='affects' row cleaned up too.
        affectsLinkRepo.deleteByAffectionAndDetected(affectionId, assetId);
        assetLinkRepo.deleteLink(affectionId, assetId, "detected_at");
        syncAffectsAssetRows(affectionId);
        repo.recomputeStatus(affectionId);
        repo.touchUpdatedAt(affectionId, OffsetDateTime.now());
        return findingService.get(findingId);
    }

    // ── Per-detected-at affects links (the new "III" model) ──────────────────────

    /**
     * Replaces the entire set of affects linked to a given {@code detected_at}
     * within an affection. The {@code affection_asset} row with role='affects'
     * is kept in sync: it exists when the asset is referenced by at least one
     * detected_at, and is removed (along with its status history) when the
     * last link disappears.
     */
    @Transactional
    public FindingDto setAffectsForDetected(Long affectionId, Long detectedAssetId, List<Long> affectsAssetIds) {
        Affection a = getEntity(affectionId);
        // Must be a real detected_at of this affection — otherwise the model is meaningless
        boolean isDetected = a.getAssetLinks().stream()
            .anyMatch(l -> "detected_at".equals(l.getRole()) && l.getAssetId().equals(detectedAssetId));
        if (!isDetected) {
            throw new IllegalArgumentException(
                "Asset " + detectedAssetId + " is not a detected_at of affection " + affectionId);
        }
        List<Long> distinct = affectsAssetIds == null ? List.of()
            : affectsAssetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!distinct.isEmpty()) {
            var found = assetRepo.findAllById(distinct);
            if (found.size() != distinct.size()) {
                throw new IllegalArgumentException("Some affect assets do not exist");
            }
        }
        affectsLinkRepo.deleteByAffectionAndDetected(affectionId, detectedAssetId);
        affectsLinkRepo.flush();
        for (Long affectsId : distinct) {
            affectsLinkRepo.save(new AffectionAffectsLink(affectionId, detectedAssetId, affectsId));
        }
        syncAffectsAssetRows(affectionId);
        repo.recomputeStatus(affectionId);
        repo.touchUpdatedAt(affectionId, OffsetDateTime.now());
        return findingService.get(a.getFindingId());
    }

    /**
     * Reconciles the legacy {@code affection_asset} role='affects' rows with the
     * set of affects assets currently referenced by any link in this affection.
     * Adds missing rows (status='open' as default) and removes rows that no
     * longer have any link — preserving status history for now (it's a soft
     * removal: reports / analytics may still want the history). The cleanup of
     * orphaned history is left to a future maintenance task.
     */
    private void syncAffectsAssetRows(Long affectionId) {
        var links = affectsLinkRepo.findByAffectionId(affectionId);
        java.util.Set<Long> linkedAffects = links.stream()
            .map(AffectionAffectsLink::getAffectsAssetId)
            .collect(java.util.stream.Collectors.toSet());
        Affection a = getEntity(affectionId);
        java.util.Set<Long> currentAffects = a.getAssetLinks().stream()
            .filter(l -> "affects".equals(l.getRole()))
            .map(AffectionAsset::getAssetId)
            .collect(java.util.stream.Collectors.toSet());
        // Add new affects: previously absent, now referenced by at least one link
        for (Long assetId : linkedAffects) {
            if (!currentAffects.contains(assetId)) {
                a.addAssetLink(assetId, "affects", null);
                // Track creation in history so analytics can see when this affect appeared
                historyRepo.save(new AffectStatusHistory(
                    affectionId, assetId, null, "open",
                    currentUserId(), currentUserName(), null, OffsetDateTime.now()));
            }
        }
        // Remove affects that are no longer referenced by any link
        for (Long assetId : currentAffects) {
            if (!linkedAffects.contains(assetId)) {
                assetLinkRepo.deleteLink(affectionId, assetId, "affects");
            }
        }
        repo.save(a);
    }

    // ── Affected-asset links ──────────────────────────────────────────────────────

    @Transactional
    public FindingDto addAffects(Long affectionId, Long assetId) {
        Affection a = getEntity(affectionId);
        assetRepo.findById(assetId).orElseThrow(() -> NotFoundException.of("asset", assetId));
        a.addAssetLink(assetId, "affects", null);
        a.setUpdatedAt(OffsetDateTime.now());
        repo.save(a);
        // New affect always starts as 'open' → recompute (will remain 'open')
        repo.recomputeStatus(affectionId);
        // Record creation in history
        historyRepo.save(new AffectStatusHistory(
            affectionId, assetId, null, "open",
            currentUserId(), currentUserName(), null, OffsetDateTime.now()));
        return findingService.get(a.getFindingId());
    }

    @Transactional
    public FindingDto removeAffects(Long affectionId, Long assetId) {
        Affection a = getEntity(affectionId);
        Long findingId = a.getFindingId();
        assetLinkRepo.deleteLink(affectionId, assetId, "affects");
        repo.recomputeStatus(affectionId);
        repo.touchUpdatedAt(affectionId, OffsetDateTime.now());
        return findingService.get(findingId);
    }

    // ── Affect status management ──────────────────────────────────────────────────

    @Transactional
    public FindingDto updateAffectStatus(Long affectionId, Long assetId, String newStatus, String note) {
        Affection a = getEntity(affectionId);
        Long findingId = a.getFindingId();

        String fromStatus = assetLinkRepo.getCurrentAffectStatus(affectionId, assetId);
        if (fromStatus == null) throw NotFoundException.of("affected asset", assetId);

        assetLinkRepo.updateAffectStatus(affectionId, assetId, newStatus);
        repo.recomputeStatus(affectionId);
        repo.touchUpdatedAt(affectionId, OffsetDateTime.now());

        historyRepo.save(new AffectStatusHistory(
            affectionId, assetId, fromStatus, newStatus,
            currentUserId(), currentUserName(),
            (note != null && !note.isBlank()) ? note.trim() : null,
            OffsetDateTime.now()));

        return findingService.get(findingId);
    }

    public com.martecyber.ares.common.PagedResponse<AffectStatusHistoryDto> getAffectHistory(
            Long affectionId, Long assetId, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(
            Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = historyRepo.findByAffectionIdAndAssetId(affectionId, assetId, pageable)
            .map(h -> new AffectStatusHistoryDto(
                h.getId(), h.getFromStatus(), h.getToStatus(),
                h.getChangedBy(), h.getChangedByName(), h.getNote(), h.getChangedAt()));
        return com.martecyber.ares.common.PagedResponse.of(result);
    }

    // ── Detection links ───────────────────────────────────────────────────────────

    @Transactional
    public FindingDto addDetection(Long affectionId, Long detectionId) {
        Affection a = getEntity(affectionId);
        Detection d = detectionRepo.findById(detectionId)
            .orElseThrow(() -> NotFoundException.of("detection", detectionId));
        a.getDetections().add(d);
        // Inherit detection's first-seen timestamp for the detected-at asset link
        if (d.getAssetId() != null) {
            OffsetDateTime when = d.getCreatedAt() != null ? d.getCreatedAt() : OffsetDateTime.now();
            a.addAssetLink(d.getAssetId(), "detected_at", when);
        }
        a.setUpdatedAt(OffsetDateTime.now());
        repo.save(a);
        return findingService.get(a.getFindingId());
    }

    @Transactional
    public FindingDto removeDetection(Long affectionId, Long detectionId) {
        Affection a = getEntity(affectionId);
        a.getDetections().removeIf(d -> d.getId().equals(detectionId));
        a.setUpdatedAt(OffsetDateTime.now());
        repo.save(a);
        return findingService.get(a.getFindingId());
    }

    // ── Deletion ──────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("affection", id);
        repo.deleteById(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Affection getEntity(Long id) {
        Affection a = repo.findById(id).orElseThrow(() -> NotFoundException.of("affection", id));
        com.martecyber.ares.findings.Finding f = findingRepo.findById(a.getFindingId())
            .orElseThrow(() -> NotFoundException.of("finding", a.getFindingId()));
        orgScope.assertProjectAccess(SecurityContextHolder.getContext().getAuthentication(), f.getProjectId());
        return a;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private String currentUserName() {
        Long id = currentUserId();
        if (id == null) return null;
        return userRepo.findById(id).map(u -> u.getDisplayName()).orElse(null);
    }
}
