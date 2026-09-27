package com.martecyber.ares.integrations.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface IntegrationScheduleRepository extends JpaRepository<IntegrationSchedule, Long> {

    List<IntegrationSchedule> findByProjectIdAndIntegrationId(Long projectId, Long integrationId);

    List<IntegrationSchedule> findByProjectId(Long projectId);

    @Query("SELECT s FROM IntegrationSchedule s WHERE s.enabled = TRUE AND (s.nextRunAt IS NULL OR s.nextRunAt <= :now)")
    List<IntegrationSchedule> findDue(OffsetDateTime now);
}
