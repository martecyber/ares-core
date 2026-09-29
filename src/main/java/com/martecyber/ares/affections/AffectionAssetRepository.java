package com.martecyber.ares.affections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AffectionAssetRepository extends JpaRepository<AffectionAsset, AffectionAssetId> {

    /** Distinct assets (any role — detected_at or affects) touched by any of the given findings.
     *  affectionId is a plain FK column on both Affection and AffectionAsset, not a mapped
     *  relation, hence the theta join. */
    @Query("""
        SELECT DISTINCT aa.asset FROM AffectionAsset aa
        JOIN Affection aff ON aff.id = aa.affectionId
        WHERE aff.findingId IN :findingIds
        """)
    List<com.martecyber.ares.assets.Asset> findDistinctAssetsByFindingIds(@Param("findingIds") Collection<Long> findingIds);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM ares.affection_asset WHERE affection_id = :affectionId AND asset_id = :assetId AND role = :role",
           nativeQuery = true)
    void deleteLink(@Param("affectionId") Long affectionId,
                    @Param("assetId") Long assetId,
                    @Param("role") String role);

    @Query(value = "SELECT status FROM ares.affection_asset WHERE affection_id = :affectionId AND asset_id = :assetId AND role = 'affects'",
           nativeQuery = true)
    String getCurrentAffectStatus(@Param("affectionId") Long affectionId, @Param("assetId") Long assetId);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE ares.affection_asset SET status = :status WHERE affection_id = :affectionId AND asset_id = :assetId AND role = 'affects'",
           nativeQuery = true)
    void updateAffectStatus(@Param("affectionId") Long affectionId,
                            @Param("assetId") Long assetId,
                            @Param("status") String status);

    /** Copy all affection_asset rows from oldId to newId, skipping conflicts. Used during asset merge. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO ares.affection_asset (affection_id, asset_id, role, observed_at, status)
        SELECT affection_id, :newId, role, observed_at, status
        FROM ares.affection_asset WHERE asset_id = :oldId
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void copyToNewAsset(@Param("oldId") Long oldId, @Param("newId") Long newId);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM ares.affection_asset WHERE asset_id = :assetId", nativeQuery = true)
    void deleteByAssetId(@Param("assetId") Long assetId);

    /** Used by import rollback to block deleting an asset that a Finding already references. */
    @Query(value = "SELECT COUNT(*) > 0 FROM ares.affection_asset WHERE asset_id = :assetId", nativeQuery = true)
    boolean existsByAssetId(@Param("assetId") Long assetId);
}
