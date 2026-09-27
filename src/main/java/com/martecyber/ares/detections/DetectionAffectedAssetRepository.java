package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DetectionAffectedAssetRepository
        extends JpaRepository<DetectionAffectedAsset, DetectionAffectedAsset.Key> {

    List<DetectionAffectedAsset> findByDetectionId(Long detectionId);

    List<DetectionAffectedAsset> findByDetectionIdIn(Collection<Long> detectionIds);

    @Modifying
    @Query("DELETE FROM DetectionAffectedAsset d WHERE d.detectionId = :detectionId")
    void deleteByDetectionId(@Param("detectionId") Long detectionId);
}
