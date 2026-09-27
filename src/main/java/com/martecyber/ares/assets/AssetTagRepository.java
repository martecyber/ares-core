package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

/** Join table between assets and the shared {@code tag} catalog. */
public interface AssetTagRepository extends CrudRepository<AssetTag, AssetTagId> {

    @Modifying
    @Query(value = """
        INSERT INTO ares.asset_tag (asset_id, tag_id, created_at)
        VALUES (:assetId, :tagId, NOW())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void assign(@Param("assetId") Long assetId, @Param("tagId") Long tagId);

    @Modifying
    @Query(value = "DELETE FROM ares.asset_tag WHERE asset_id = :assetId AND tag_id = :tagId", nativeQuery = true)
    void unassign(@Param("assetId") Long assetId, @Param("tagId") Long tagId);

    /** (asset_id, tag_id, name, color) rows for a batch of assets — used to populate
     *  Asset.tags for a list without one query per row. */
    @Query(value = """
        SELECT at.asset_id AS assetId, t.id AS id, t.name AS name, t.color AS color
        FROM ares.asset_tag at
        JOIN ares.tag t ON t.id = at.tag_id
        WHERE at.asset_id IN :assetIds
        ORDER BY t.name ASC
        """, nativeQuery = true)
    List<AssetTagRow> findTagsForAssetIds(@Param("assetIds") Collection<Long> assetIds);

    interface AssetTagRow {
        Long getAssetId();
        Long getId();
        String getName();
        String getColor();
    }
}
