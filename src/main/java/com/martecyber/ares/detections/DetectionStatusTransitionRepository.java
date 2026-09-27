package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DetectionStatusTransitionRepository extends JpaRepository<DetectionStatusTransition, DetectionStatusTransitionId> {

    @Query("SELECT t.id.toStatusId FROM DetectionStatusTransition t WHERE t.id.fromStatusId = :fromStatusId")
    List<Long> findToStatusIdsByFromStatusId(@Param("fromStatusId") Long fromStatusId);
}
