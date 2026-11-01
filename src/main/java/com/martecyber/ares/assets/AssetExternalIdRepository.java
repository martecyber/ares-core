package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetExternalIdRepository extends JpaRepository<AssetExternalId, Long> {
    Optional<AssetExternalId> findByOrganizationIdAndToolAndExternalId(Long organizationId, String tool, String externalId);
    List<AssetExternalId> findByAssetIdOrderByToolAscExternalIdAsc(Long assetId);

    /** Merge: the surviving asset inherits the merged-away asset's tool ids (same org, so the
     *  (org, tool, external_id) uniqueness can't collide). */
    @Modifying
    @Query("UPDATE AssetExternalId e SET e.assetId = :targetId WHERE e.assetId = :sourceId")
    int reassign(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
