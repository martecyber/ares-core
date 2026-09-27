package com.martecyber.ares.kb.wordlists;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface KbWordlistRepoRepository extends JpaRepository<KbWordlistRepo, Long> {

    @Query("""
        SELECT r FROM KbWordlistRepo r
        WHERE r.autoSync = true
          AND (r.lastSyncAt IS NULL
            OR r.lastSyncAt < :before)
          AND (r.lastSyncStatus IS NULL OR r.lastSyncStatus <> 'running')
        """)
    List<KbWordlistRepo> findDueForSync(OffsetDateTime before);
}
