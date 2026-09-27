package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DetectionHttpSampleRepository extends JpaRepository<DetectionHttpSample, Long> {
    List<DetectionHttpSample> findByDetectionId(Long detectionId);
    void deleteByDetectionId(Long detectionId);
}
