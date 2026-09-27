package com.martecyber.ares.detections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DetectionRepository extends JpaRepository<Detection, Long>, JpaSpecificationExecutor<Detection> {

    /** Used by import rollback to check whether an asset can be safely deleted — i.e. no
     *  live detection (from this or any other import) still points to it. */
    @Query("SELECT d.id FROM Detection d WHERE d.assetId = :assetId")
    java.util.List<Long> findIdsByAssetId(@Param("assetId") Long assetId);

    /** Idempotency lookup for externally-ingested detections (e.g. Caido findings). */
    java.util.Optional<Detection> findFirstByProjectIdAndSourceTypeAndSourceTemplateId(
            Long projectId, String sourceType, String sourceTemplateId);

    /** All detections in a project — used to backfill DetectionIterationStat from history. */
    java.util.List<Detection> findByProjectId(Long projectId);

    /** Detections with at least one recorded DetectionIterationStat row whose label falls in
     *  [fromLabel, toLabel] (inclusive) — Detection itself has no iteration column, only its
     *  per-transition stat rows do. Used by report generation to scope the "detections" template
     *  data to the same iteration window as the report's already-pinned findings. */
    @Query("""
           SELECT DISTINCT d FROM Detection d
           WHERE d.projectId = :projectId
             AND d.id IN (
               SELECT s.detectionId FROM DetectionIterationStat s
               WHERE s.projectId = :projectId
                 AND s.iterationLabel >= :fromLabel AND s.iterationLabel <= :toLabel
             )
           ORDER BY d.createdAt ASC
           """)
    java.util.List<Detection> findByProjectAndIterationLabelRange(
        @Param("projectId") Long projectId,
        @Param("fromLabel") String fromLabel,
        @Param("toLabel") String toLabel);

    /** {@code orgIds}, when non-null, restricts results to a caller-accessible set of orgs
     *  (non-admin callers who didn't supply an explicit projectId). */
    @Query("""
           SELECT d FROM Detection d
           JOIN com.martecyber.ares.projects.Project p ON p.id = d.projectId
           WHERE
           (:projectId IS NULL OR d.projectId = :projectId) AND
           (:orgIds IS NULL OR p.organizationId IN :orgIds) AND
           (:noAssetFilter = true OR d.assetId IN :assetIds) AND
           (:noSeverityFilter = true OR d.severity IN :severities) AND
           (:noStatusFilter = true OR d.status IN :statuses) AND
           (:noSourceFilter = true OR d.sourceType IN :sources) AND
           (:q IS NULL OR LOWER(d.title) LIKE :qLike
               OR (d.description IS NOT NULL AND LOWER(d.description) LIKE :qLike)
               OR (d.sourceTemplateId IS NOT NULL AND LOWER(d.sourceTemplateId) LIKE :qLike))
           """)
    Page<Detection> filter(@Param("projectId") Long projectId,
                           @Param("orgIds") java.util.Collection<Long> orgIds,
                           @Param("noAssetFilter") boolean noAssetFilter,
                           @Param("assetIds") java.util.List<Long> assetIds,
                           @Param("noSeverityFilter") boolean noSeverityFilter,
                           @Param("severities") java.util.List<String> severities,
                           @Param("noStatusFilter") boolean noStatusFilter,
                           @Param("statuses") java.util.List<String> statuses,
                           @Param("noSourceFilter") boolean noSourceFilter,
                           @Param("sources") java.util.List<String> sources,
                           @Param("q") String q,
                           @Param("qLike") String qLike,
                           Pageable pageable);

    @Query("SELECT DISTINCT d.sourceType FROM Detection d WHERE d.projectId = :engId AND d.sourceType IS NOT NULL")
    java.util.List<String> findDistinctSources(@Param("engId") Long engId);

    @Query("SELECT DISTINCT d.assetId FROM Detection d WHERE d.projectId = :engId AND d.assetId IS NOT NULL")
    java.util.List<Long> findDistinctAssetIds(@Param("engId") Long engId);

    java.util.Optional<Detection> findByProjectIdAndDedupHash(Long projectId, String dedupHash);

    @Query("SELECT DISTINCT d FROM Detection d LEFT JOIN FETCH d.references WHERE d.id = :id")
    java.util.Optional<Detection> findByIdWithReferences(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Detection d SET d.assetId = :newId WHERE d.assetId = :oldId")
    void reassignAsset(@Param("oldId") Long oldId, @Param("newId") Long newId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Detection d WHERE d.assetId IN :assetIds")
    void deleteByAssetIdIn(@Param("assetIds") java.util.Collection<Long> assetIds);
}
