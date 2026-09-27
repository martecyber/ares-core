package com.martecyber.ares.kb.cve;

import jakarta.persistence.*;

import java.time.Instant;

/** Singleton row tracking the last completed CVE git sync. */
@Entity
@Table(name = "cve_sync_state", schema = "ares")
public class CveSyncState {

    @Id
    private String id = "singleton";

    @Column(name = "last_commit")
    private String lastCommit;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "total_processed", nullable = false)
    private long totalProcessed;

    public CveSyncState() {}

    public CveSyncState(String lastCommit, Instant lastSyncedAt, long totalProcessed) {
        this.lastCommit = lastCommit;
        this.lastSyncedAt = lastSyncedAt;
        this.totalProcessed = totalProcessed;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLastCommit() { return lastCommit; }
    public void setLastCommit(String lastCommit) { this.lastCommit = lastCommit; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public long getTotalProcessed() { return totalProcessed; }
    public void setTotalProcessed(long totalProcessed) { this.totalProcessed = totalProcessed; }
}
