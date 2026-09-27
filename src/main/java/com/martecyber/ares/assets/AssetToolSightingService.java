package com.martecyber.ares.assets;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Records which tools have discovered each asset (first/last seen timestamps), scoped by which
 * project's sync/import produced the sighting — see {@link AssetToolSighting#getProjectId()}'s
 * own doc comment for why that scoping exists (a security fix: Asset is organization-scoped and
 * the same asset can be linked to several different projects, so an unscoped sighting leaked one
 * project's tool provenance into every other project — and the organization level — that also
 * happened to reference the same asset).
 * Recording is idempotent: calling upsert with the same (assetId, projectId, tool) triple
 * just advances last_seen_at.
 */
@Service
public class AssetToolSightingService {

    public record AssetToolSightingDto(Long id, String tool, OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt) {
        public static AssetToolSightingDto from(AssetToolSighting s) {
            return new AssetToolSightingDto(s.getId(), s.getTool(), s.getFirstSeenAt(), s.getLastSeenAt());
        }
    }

    /** Organization-level view — which PROJECTS have discovered this asset, never which tool
     *  each one used (that detail is exclusive to the project itself). */
    public record ProjectSightingDto(Long projectId, String projectName, OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt) {
        public static ProjectSightingDto from(AssetToolSightingRepository.ProjectSightingRow r) {
            return new ProjectSightingDto(r.getProjectId(), r.getProjectName(),
                r.getFirstSeenAt().atOffset(java.time.ZoneOffset.UTC), r.getLastSeenAt().atOffset(java.time.ZoneOffset.UTC));
        }
    }

    private final AssetToolSightingRepository repo;

    public AssetToolSightingService(AssetToolSightingRepository repo) {
        this.repo = repo;
    }

    /** REQUIRES_NEW: callers (import pipelines) invoke this once per resolved asset inside their
     *  own larger transaction. The catch below is meant to swallow non-fatal failures (an FK
     *  violation if the asset/project was just deleted concurrently, or a transient deadlock on
     *  the sighting's unique index under concurrent imports) — but catching a Java exception does
     *  NOT undo Postgres marking the ambient transaction aborted after a failed statement. With
     *  the default REQUIRED propagation this silently poisoned the caller's whole transaction, so
     *  every later statement in that same import (including the final ScanImport save) blew up
     *  with "current transaction is aborted" — a real production incident. Isolating this into its
     *  own transaction lets a failure roll back only its own tiny insert. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void upsert(Long assetId, Long projectId, String tool) {
        if (assetId == null || projectId == null || tool == null || tool.isBlank()) return;
        try {
            repo.upsert(assetId, projectId, tool.trim());
        } catch (Exception ignored) {
            // FK violation if asset/project was just deleted concurrently — non-fatal
        }
    }

    /** Project-context asset detail page — the only place tool-level provenance is shown. */
    public List<AssetToolSightingDto> listByAssetAndProject(Long assetId, Long projectId) {
        return repo.findByAssetIdAndProjectIdOrderByLastSeenAtDesc(assetId, projectId).stream()
            .map(AssetToolSightingDto::from)
            .toList();
    }

    /** Organization-context asset detail page. */
    public List<ProjectSightingDto> listProjectsForAsset(Long assetId, Long organizationId) {
        return repo.findProjectSightingsForAsset(assetId, organizationId).stream()
            .map(ProjectSightingDto::from)
            .toList();
    }
}
