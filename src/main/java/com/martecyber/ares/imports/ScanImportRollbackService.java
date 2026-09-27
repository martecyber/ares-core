package com.martecyber.ares.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.affections.AffectionAssetRepository;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.imports.dto.ScanImportRollbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rolls a single scan import's effects back: reverts what it updated (only the fields it
 * actually touched — see {@link AssetImportHelper}/{@link ImportService}'s snapshot capture)
 * and deletes what it created, skipping (not failing) any row that's unsafe to touch — either
 * because a later, still-live import has since changed it again, or because it's already
 * referenced by a Finding. Safe to re-run: only {@code scan_import_change} rows still pending
 * (reverted_at IS NULL) are considered, so a partial rollback can simply be retried later.
 */
@Service
public class ScanImportRollbackService {

    private static final Logger log = LoggerFactory.getLogger(ScanImportRollbackService.class);

    private final ScanImportRepository importRepo;
    private final ScanImportChangeRepository changeRepo;
    private final AssetRepository assetRepo;
    private final DetectionRepository detectionRepo;
    private final AffectionRepository affectionRepo;
    private final AffectionAssetRepository affectionAssetRepo;
    private final com.martecyber.ares.detections.DetectionStatusRepository detectionStatusRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScanImportRollbackService(ScanImportRepository importRepo,
                                     ScanImportChangeRepository changeRepo,
                                     AssetRepository assetRepo,
                                     DetectionRepository detectionRepo,
                                     AffectionRepository affectionRepo,
                                     AffectionAssetRepository affectionAssetRepo,
                                     com.martecyber.ares.detections.DetectionStatusRepository detectionStatusRepo) {
        this.importRepo = importRepo;
        this.changeRepo = changeRepo;
        this.assetRepo = assetRepo;
        this.detectionRepo = detectionRepo;
        this.affectionRepo = affectionRepo;
        this.affectionAssetRepo = affectionAssetRepo;
        this.detectionStatusRepo = detectionStatusRepo;
    }

    /** Computes what a rollback would do, without changing anything. */
    public ScanImportRollbackResult preview(Long projectId, Long scanImportId) {
        return plan(projectId, scanImportId, true);
    }

    /** Executes the rollback. Partial: rows that are unsafe to touch are skipped and reported
     *  in {@code blocked} rather than aborting the whole run. */
    @Transactional
    public ScanImportRollbackResult rollback(Long projectId, Long scanImportId) {
        return plan(projectId, scanImportId, false);
    }

    private ScanImportRollbackResult plan(Long projectId, Long scanImportId, boolean dryRun) {
        ScanImport scanImport = importRepo.findById(scanImportId)
            .orElseThrow(() -> NotFoundException.of("scan_import", scanImportId));
        if (!scanImport.getProjectId().equals(projectId)) {
            throw NotFoundException.of("scan_import", scanImportId);
        }

        List<ScanImportChange> pending = changeRepo.findByScanImportIdAndRevertedAtIsNullOrderByIdAsc(scanImportId);
        List<ScanImportRollbackResult.BlockedItem> blocked = new ArrayList<>();
        int detectionsReverted = 0, assetsReverted = 0, detectionsDeleted = 0, assetsDeleted = 0;

        // ── 1) Revert 'updated' detections ────────────────────────────────────────
        // Multiple change rows can exist for the same detection within this one import (e.g. a
        // duplicate entry re-processed in the same file) — only the EARLIEST snapshot reflects
        // the true pre-import state, so group by entity and use that one.
        Set<Long> revertedDetectionIds = new HashSet<>();
        for (var group : groupByEntity(pending, "detection", "updated").entrySet()) {
            Long detectionId = group.getKey();
            List<ScanImportChange> rows = group.getValue(); // ascending by id — first is earliest
            ScanImportChange earliest = rows.get(0);
            ScanImportChange latest = rows.get(rows.size() - 1);
            if (changeRepo.existsNewerUnrevertedChange("detection", detectionId, latest.getCreatedAt())) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("detection", detectionId,
                    "A later import has since updated this detection again"));
                continue;
            }
            Detection d = detectionRepo.findById(detectionId).orElse(null);
            if (d == null) { markReverted(rows, dryRun); continue; } // already gone — nothing to revert
            try {
                Map<?, ?> prev = objectMapper.readValue(earliest.getPrevValues(), Map.class);
                if (!dryRun) {
                    Object occ = prev.get("occurrenceCount");
                    d.setOccurrenceCount(occ != null ? ((Number) occ).intValue() : d.getOccurrenceCount());
                    Object lastSeen = prev.get("lastSeen");
                    d.setLastSeen(lastSeen != null ? OffsetDateTime.parse((String) lastSeen) : null);
                    Object status = prev.get("status");
                    if (status != null) {
                        d.setStatus((String) status);
                        detectionStatusRepo.findByName((String) status)
                            .ifPresent(s -> d.setStatusId(s.getId()));
                    }
                    d.setUpdatedAt(OffsetDateTime.now());
                    detectionRepo.save(d);
                }
                markReverted(rows, dryRun);
                detectionsReverted++;
            } catch (Exception e) {
                log.warn("Failed to revert detection {}: {}", detectionId, e.getMessage());
                blocked.add(new ScanImportRollbackResult.BlockedItem("detection", detectionId,
                    "Failed to parse/restore previous values: " + e.getMessage()));
            }
        }

        // ── 2) Revert 'updated' assets ─────────────────────────────────────────────
        for (var group : groupByEntity(pending, "asset", "updated").entrySet()) {
            Long assetId = group.getKey();
            List<ScanImportChange> rows = group.getValue();
            ScanImportChange earliest = rows.get(0);
            ScanImportChange latest = rows.get(rows.size() - 1);
            if (changeRepo.existsNewerUnrevertedChange("asset", assetId, latest.getCreatedAt())) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("asset", assetId,
                    "A later import has since updated this asset again"));
                continue;
            }
            Asset a = assetRepo.findById(assetId).orElse(null);
            if (a == null) { markReverted(rows, dryRun); continue; }
            try {
                Map<?, ?> prev = objectMapper.readValue(earliest.getPrevValues(), Map.class);
                if (!dryRun) {
                    Object identifier = prev.get("identifier");
                    if (identifier != null) a.setIdentifier((String) identifier);
                    Object hostnames = prev.get("hostnames");
                    if (hostnames instanceof List<?> list) {
                        a.setHostnames(list.stream().map(String::valueOf).collect(Collectors.toCollection(ArrayList::new)));
                    }
                    Object metadata = prev.get("metadata");
                    a.setMetadata(metadata != null ? (String) metadata : "{}");
                    a.setUpdatedAt(OffsetDateTime.now());
                    assetRepo.save(a);
                }
                markReverted(rows, dryRun);
                assetsReverted++;
            } catch (Exception e) {
                log.warn("Failed to revert asset {}: {}", assetId, e.getMessage());
                blocked.add(new ScanImportRollbackResult.BlockedItem("asset", assetId,
                    "Failed to parse/restore previous values: " + e.getMessage()));
            }
        }

        // ── 3) Delete 'created' detections ──────────────────────────────────────────
        Set<Long> deletedDetectionIds = new HashSet<>();
        for (ScanImportChange c : filterEntity(pending, "detection", "created")) {
            Long detectionId = c.getEntityId();
            if (changeRepo.existsNewerUnrevertedChange("detection", detectionId, c.getCreatedAt())) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("detection", detectionId,
                    "A later import has since updated this detection"));
                continue;
            }
            if (!affectionRepo.findByDetectionId(detectionId).isEmpty()) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("detection", detectionId,
                    "Already linked to a finding — remove that link first"));
                continue;
            }
            if (!detectionRepo.existsById(detectionId)) { markReverted(List.of(c), dryRun); continue; }
            if (!dryRun) detectionRepo.deleteById(detectionId);
            markReverted(List.of(c), dryRun);
            deletedDetectionIds.add(detectionId);
            detectionsDeleted++;
        }

        // ── 4) Delete 'created' assets ───────────────────────────────────────────────
        // Re-checked after step 3 so an asset whose only remaining detections were just
        // deleted above doesn't get flagged as still-in-use.
        for (ScanImportChange c : filterEntity(pending, "asset", "created")) {
            Long assetId = c.getEntityId();
            if (changeRepo.existsNewerUnrevertedChange("asset", assetId, c.getCreatedAt())) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("asset", assetId,
                    "A later import has since updated this asset"));
                continue;
            }
            if (affectionAssetRepo.existsByAssetId(assetId)) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("asset", assetId,
                    "Already linked to a finding — remove that link first"));
                continue;
            }
            List<Long> stillPointing = detectionRepo.findIdsByAssetId(assetId).stream()
                .filter(id -> !deletedDetectionIds.contains(id))
                .toList();
            if (!stillPointing.isEmpty()) {
                blocked.add(new ScanImportRollbackResult.BlockedItem("asset", assetId,
                    "Still referenced by " + stillPointing.size() + " detection(s) from another import"));
                continue;
            }
            if (!assetRepo.existsById(assetId)) { markReverted(List.of(c), dryRun); continue; }
            if (!dryRun) assetRepo.deleteById(assetId);
            markReverted(List.of(c), dryRun);
            assetsDeleted++;
        }

        return new ScanImportRollbackResult(assetsDeleted, assetsReverted, detectionsDeleted, detectionsReverted, blocked);
    }

    private static List<ScanImportChange> filterEntity(List<ScanImportChange> changes, String entityType, String action) {
        return changes.stream()
            .filter(c -> entityType.equals(c.getEntityType()) && action.equals(c.getAction()))
            .toList();
    }

    private static Map<Long, List<ScanImportChange>> groupByEntity(List<ScanImportChange> changes, String entityType, String action) {
        return filterEntity(changes, entityType, action).stream()
            .collect(Collectors.groupingBy(ScanImportChange::getEntityId, LinkedHashMap::new, Collectors.toList()));
    }

    private void markReverted(List<ScanImportChange> rows, boolean dryRun) {
        if (dryRun) return;
        OffsetDateTime now = OffsetDateTime.now();
        for (ScanImportChange c : rows) c.setRevertedAt(now);
        changeRepo.saveAll(rows);
    }
}
