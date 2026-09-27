package com.martecyber.ares.agents.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AgentTaskScheduleRepository extends JpaRepository<AgentTaskSchedule, Long> {

    List<AgentTaskSchedule> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    @Query("""
        SELECT s FROM AgentTaskSchedule s
        WHERE s.enabled = true
          AND s.nextRunAt IS NOT NULL
          AND s.nextRunAt <= :now
        """)
    List<AgentTaskSchedule> findDue(@Param("now") OffsetDateTime now);
}
