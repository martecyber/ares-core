package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DetectionStatusRepository extends JpaRepository<DetectionStatus, Long> {
    Optional<DetectionStatus> findByName(String name);
    List<DetectionStatus> findAllByOrderByIdAsc();
}
