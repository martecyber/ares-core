package com.martecyber.ares.projects;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ProjectAssetAccessRepository extends JpaRepository<ProjectAssetAccess, ProjectAssetAccessId> {
    List<ProjectAssetAccess> findByProjectId(Long projectId);
    List<ProjectAssetAccess> findByAssetId(Long assetId);

    /** Platform-wide (no project filter) — used for the third-party reclassification sweep. */
    List<ProjectAssetAccess> findByScopeOverrideFalse();
    boolean existsByProjectIdAndAssetId(Long projectId, Long assetId);
    void deleteByProjectIdAndAssetId(Long projectId, Long assetId);

    /** Idempotent insert — silently ignores duplicates. Used by the import pipeline. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO ares.project_asset_access (project_id, asset_id) " +
                   "VALUES (:engId, :assetId) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    void linkIfAbsent(@Param("engId") Long engId, @Param("assetId") Long assetId);

    /**
     * Bulk-updates scope_status for a batch of assets in one query.
     * Called by AssetScopeClassifier to avoid N individual saves.
     * Only updates rows where scope_override = false (user overrides are preserved).
     */
    @Modifying
    @Transactional
    @Query("UPDATE ProjectAssetAccess paa SET paa.scopeStatus = :status " +
           "WHERE paa.projectId = :projectId AND paa.assetId IN :assetIds " +
           "AND paa.scopeOverride = false")
    int updateScopeStatusForAssets(@Param("projectId") Long projectId,
                                   @Param("assetIds") Collection<Long> assetIds,
                                   @Param("status") String status);
}
