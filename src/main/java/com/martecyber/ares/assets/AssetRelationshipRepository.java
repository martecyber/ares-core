package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface AssetRelationshipRepository extends JpaRepository<AssetRelationship, AssetRelationshipId> {
    List<AssetRelationship> findByFromAssetId(Long fromAssetId);
    List<AssetRelationship> findByToAssetId(Long toAssetId);
    List<AssetRelationship> findByToAssetIdAndType(Long toAssetId, String type);
    List<AssetRelationship> findByFromAssetIdAndType(Long fromAssetId, String type);
    void deleteByFromAssetIdAndToAssetIdAndType(Long fromAssetId, Long toAssetId, String type);

    /**
     * Relationships touching any asset in {@code ids} (either endpoint). Used for
     * incremental, frontier-scoped BFS so a hop-limited neighborhood query only ever
     * touches the rows actually within range — not the org's entire relationship graph.
     */
    List<AssetRelationship> findByFromAssetIdInOrToAssetIdIn(Collection<Long> fromIds, Collection<Long> toIds);

    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM AssetRelationship r WHERE r.fromAssetId IN " +
        "(SELECT a.id FROM Asset a WHERE a.organizationId = :orgId)")
    List<AssetRelationship> findByOrganizationId(@org.springframework.data.repository.query.Param("orgId") Long orgId);

    /** Returns relationships where BOTH endpoints are visible in the project, excluding
     *  relationships the project has hidden (project_relationship_hidden). */
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT r.* FROM ares.asset_relationships r " +
        "WHERE r.from_asset_id IN " +
        "  (SELECT asset_id FROM ares.project_asset_access WHERE project_id = :engId) " +
        "AND r.to_asset_id IN " +
        "  (SELECT asset_id FROM ares.project_asset_access WHERE project_id = :engId) " +
        "AND NOT EXISTS (SELECT 1 FROM ares.project_relationship_hidden h " +
        "  WHERE h.project_id = :engId AND h.from_asset_id = r.from_asset_id " +
        "  AND h.to_asset_id = r.to_asset_id AND h.type = r.type)",
        nativeQuery = true)
    List<AssetRelationship> findByProjectId(
        @org.springframework.data.repository.query.Param("engId") Long engId);
}
