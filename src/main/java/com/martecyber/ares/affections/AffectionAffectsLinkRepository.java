package com.martecyber.ares.affections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AffectionAffectsLinkRepository
        extends JpaRepository<AffectionAffectsLink, AffectionAffectsLink.Key> {

    List<AffectionAffectsLink> findByAffectionId(Long affectionId);

    List<AffectionAffectsLink> findByAffectionIdAndDetectedAssetId(Long affectionId, Long detectedAssetId);

    List<AffectionAffectsLink> findByAffectionIdIn(Collection<Long> affectionIds);

    @Modifying
    @Query("DELETE FROM AffectionAffectsLink l "
         + "WHERE l.affectionId = :affectionId AND l.detectedAssetId = :detectedAssetId")
    void deleteByAffectionAndDetected(@Param("affectionId") Long affectionId,
                                       @Param("detectedAssetId") Long detectedAssetId);

    @Modifying
    @Query("DELETE FROM AffectionAffectsLink l "
         + "WHERE l.affectionId = :affectionId AND l.detectedAssetId = :detectedAssetId "
         + "AND l.affectsAssetId = :affectsAssetId")
    void deleteOne(@Param("affectionId") Long affectionId,
                    @Param("detectedAssetId") Long detectedAssetId,
                    @Param("affectsAssetId") Long affectsAssetId);

    /** Count remaining links for a given affects asset across ALL detected_at parents. */
    @Query("SELECT COUNT(l) FROM AffectionAffectsLink l "
         + "WHERE l.affectionId = :affectionId AND l.affectsAssetId = :affectsAssetId")
    long countByAffectionAndAffects(@Param("affectionId") Long affectionId,
                                     @Param("affectsAssetId") Long affectsAssetId);
}
