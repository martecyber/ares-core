package com.martecyber.ares.affections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AffectStatusHistoryRepository extends JpaRepository<AffectStatusHistory, Long> {

    /** Newest first — page 0 is "what just happened," same convention as
     *  DetectionStatusHistoryRepository#findByDetectionIdOrderByChangedAtDesc. Paginated rather
     *  than returning the whole history in one response: a long-lived retest cycle can accumulate
     *  many status changes on one (affection, asset) pair. */
    @Query("SELECT h FROM AffectStatusHistory h " +
           "WHERE h.affectionId = :affectionId AND h.assetId = :assetId " +
           "ORDER BY h.changedAt DESC")
    Page<AffectStatusHistory> findByAffectionIdAndAssetId(
        @Param("affectionId") Long affectionId,
        @Param("assetId") Long assetId,
        Pageable pageable);

    /** Status changes recorded since a given instant, across a set of affections — used to scope a
     *  RETEST report's per-finding history to only what happened during that retest (since linking). */
    @Query("SELECT h FROM AffectStatusHistory h " +
           "WHERE h.affectionId IN :affectionIds AND h.changedAt >= :since " +
           "ORDER BY h.changedAt ASC")
    List<AffectStatusHistory> findByAffectionIdInAndChangedAtAfter(
        @Param("affectionIds") List<Long> affectionIds,
        @Param("since") OffsetDateTime since);
}
