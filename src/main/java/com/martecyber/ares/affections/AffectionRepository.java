package com.martecyber.ares.affections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AffectionRepository extends JpaRepository<Affection, Long> {

    @Query("SELECT a FROM Affection a LEFT JOIN FETCH a.assetLinks al LEFT JOIN FETCH al.asset " +
           "WHERE a.findingId = :findingId ORDER BY a.createdAt DESC")
    List<Affection> findByFindingIdWithAssets(@Param("findingId") Long findingId);

    long countByFindingId(Long findingId);

    @Query("SELECT DISTINCT a FROM Affection a JOIN FETCH a.detections WHERE a.findingId IN :findingIds")
    List<Affection> findByFindingIdsWithDetections(@Param("findingIds") java.util.Collection<Long> findingIds);

    @Query("SELECT DISTINCT a FROM Affection a JOIN FETCH a.detections WHERE a.findingId = :findingId")
    List<Affection> findByFindingIdWithDetections(@Param("findingId") Long findingId);

    @Query("SELECT a FROM Affection a JOIN a.detections d WHERE d.id = :detectionId")
    List<Affection> findByDetectionId(@Param("detectionId") Long detectionId);

    Page<Affection> findByFindingIdOrderByCreatedAtDesc(Long findingId, Pageable pageable);

    @Query("SELECT DISTINCT a.findingId FROM Affection a WHERE a.findingId IN :ids")
    List<Long> findFindingIdsWithAffections(@Param("ids") java.util.Collection<Long> ids);

    @Query("SELECT DISTINCT a.findingId FROM Affection a WHERE a.findingId IN :ids AND a.status = 'open'")
    List<Long> findFindingIdsWithOpenAffections(@Param("ids") java.util.Collection<Long> ids);

    @Modifying
    @Query("UPDATE Affection a SET a.updatedAt = :now WHERE a.id = :id")
    void touchUpdatedAt(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** Recomputes open/closed status from current affect statuses. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE ares.affection SET status =
            CASE
                WHEN NOT EXISTS (
                    SELECT 1 FROM ares.affection_asset
                    WHERE affection_id = :id AND role = 'affects'
                ) THEN 'open'
                WHEN EXISTS (
                    SELECT 1 FROM ares.affection_asset
                    WHERE affection_id = :id AND role = 'affects' AND status = 'open'
                ) THEN 'open'
                ELSE 'closed'
            END
        WHERE id = :id
        """, nativeQuery = true)
    void recomputeStatus(@Param("id") Long id);
}
