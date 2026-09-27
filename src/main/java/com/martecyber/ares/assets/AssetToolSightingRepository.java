package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssetToolSightingRepository extends JpaRepository<AssetToolSighting, Long> {

    /** Project-scoped — the only listing an asset's project-context detail page should ever use;
     *  see {@link AssetToolSighting#getProjectId()}'s own doc comment for why. */
    List<AssetToolSighting> findByAssetIdAndProjectIdOrderByLastSeenAtDesc(Long assetId, Long projectId);

    @Modifying
    @Query(value = """
        INSERT INTO ares.asset_tool_sighting(asset_id, project_id, tool, first_seen_at, last_seen_at)
        VALUES (:assetId, :projectId, :tool, now(), now())
        ON CONFLICT (asset_id, tool, project_id) DO UPDATE SET last_seen_at = now()
        """, nativeQuery = true)
    void upsert(@Param("assetId") Long assetId, @Param("projectId") Long projectId, @Param("tool") String tool);

    /** One row per distinct project (within the given organization) that has ever recorded a tool
     *  sighting for this asset — deliberately never exposes which tool, only the project and the
     *  earliest/latest sighting instants across all of that project's tools, for the
     *  organization-level asset detail page (see AssetToolSightingService#listProjectsForAsset's
     *  own doc comment for why raw tool provenance must not surface there). */
    @Query(value = """
        SELECT p.id AS projectId, p.name AS projectName,
               MIN(s.first_seen_at) AS firstSeenAt, MAX(s.last_seen_at) AS lastSeenAt
        FROM ares.asset_tool_sighting s
        JOIN ares.project p ON p.id = s.project_id
        WHERE s.asset_id = :assetId AND p.organization_id = :organizationId
        GROUP BY p.id, p.name
        ORDER BY MAX(s.last_seen_at) DESC
        """, nativeQuery = true)
    List<ProjectSightingRow> findProjectSightingsForAsset(@Param("assetId") Long assetId, @Param("organizationId") Long organizationId);

    /** Native-query projection — the driver hands back {@code java.time.Instant} for a
     *  {@code timestamptz} column, and Spring's interface-projection mechanism won't auto-convert
     *  that to {@code OffsetDateTime} (no matching Converter registered), so this must declare
     *  {@code Instant} to avoid an {@code UnsupportedOperationException} at query time. */
    interface ProjectSightingRow {
        Long getProjectId();
        String getProjectName();
        java.time.Instant getFirstSeenAt();
        java.time.Instant getLastSeenAt();
    }
}
