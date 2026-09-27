package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DetectionScoreRepository extends JpaRepository<DetectionScore, Long> {
    Optional<DetectionScore> findByDetectionIdAndIsDefaultTrue(Long detectionId);
}
