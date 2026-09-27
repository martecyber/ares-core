package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FindingStatusTransitionRepository extends JpaRepository<FindingStatusTransition, FindingStatusTransitionId> {

    @Query("SELECT t.id.toStatusId FROM FindingStatusTransition t WHERE t.id.fromStatusId = :fromStatusId")
    List<Long> findToStatusIdsByFromStatusId(@Param("fromStatusId") Long fromStatusId);
}
