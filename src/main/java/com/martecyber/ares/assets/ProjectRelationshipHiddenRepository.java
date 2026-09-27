package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProjectRelationshipHiddenRepository
    extends JpaRepository<ProjectRelationshipHidden, ProjectRelationshipHiddenId> {

    boolean existsByProjectIdAndFromAssetIdAndToAssetIdAndType(
        Long projectId, Long fromAssetId, Long toAssetId, String type);

    void deleteByProjectIdAndFromAssetIdAndToAssetIdAndType(
        Long projectId, Long fromAssetId, Long toAssetId, String type);

    /** Idempotent insert — silently ignores duplicates. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO ares.project_relationship_hidden " +
                   "(project_id, from_asset_id, to_asset_id, type) " +
                   "VALUES (:projectId, :fromAssetId, :toAssetId, :type) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    void hideIfAbsent(@Param("projectId") Long projectId, @Param("fromAssetId") Long fromAssetId,
                       @Param("toAssetId") Long toAssetId, @Param("type") String type);
}
