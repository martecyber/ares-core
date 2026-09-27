package com.martecyber.ares.kb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface KbSyncScheduleRepository extends JpaRepository<KbSyncSchedule, Long> {

    List<KbSyncSchedule> findBySyncTypeOrderByCreatedAtAsc(String syncType);

    @Query("SELECT s FROM KbSyncSchedule s WHERE s.enabled = TRUE AND (s.nextRunAt IS NULL OR s.nextRunAt <= :now)")
    List<KbSyncSchedule> findDue(OffsetDateTime now);
}
