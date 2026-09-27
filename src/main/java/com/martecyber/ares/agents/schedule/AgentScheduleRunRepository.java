package com.martecyber.ares.agents.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentScheduleRunRepository extends JpaRepository<AgentScheduleRun, Long> {

    List<AgentScheduleRun> findByScheduleIdOrderByStartedAtDesc(Long scheduleId);

    /** Atomically increments the completed/failed counters for a run. */
    @Modifying
    @Query(value = """
        UPDATE ares.agent_schedule_run
        SET completed_count = completed_count + :completedDelta,
            failed_count    = failed_count    + :failedDelta
        WHERE id = :runId
        """, nativeQuery = true)
    void incrementCounters(@Param("runId") Long runId,
                           @Param("completedDelta") int completedDelta,
                           @Param("failedDelta") int failedDelta);

    /**
     * Average totalDurationMs over the most recent N closed runs of this schedule.
     * NULL when there are no closed runs yet.
     */
    @Query(value = """
        SELECT AVG(total_duration_ms)::BIGINT FROM (
            SELECT total_duration_ms
            FROM ares.agent_schedule_run
            WHERE schedule_id = :scheduleId
              AND completed_at IS NOT NULL
              AND total_duration_ms IS NOT NULL
            ORDER BY started_at DESC
            LIMIT :window
        ) recent
        """, nativeQuery = true)
    Optional<Long> avgDurationOverWindow(@Param("scheduleId") Long scheduleId,
                                         @Param("window") int window);
}
