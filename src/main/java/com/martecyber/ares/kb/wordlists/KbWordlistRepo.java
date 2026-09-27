package com.martecyber.ares.kb.wordlists;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kb_wordlist_repo", schema = "ares")
public class KbWordlistRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_url", nullable = false, length = 1024)
    private String repoUrl;

    @Column(name = "branch", nullable = false, length = 255)
    private String branch = "main";

    @Column(name = "path_filter", length = 1024)
    private String pathFilter;

    @Column(name = "github_token", length = 4096)
    private String githubToken;

    @Column(name = "auto_sync", nullable = false)
    private boolean autoSync = false;

    @Column(name = "sync_interval_hours", nullable = false)
    private int syncIntervalHours = 24;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "last_sync_status", length = 32)
    private String lastSyncStatus;

    @Column(name = "last_sync_error")
    private String lastSyncError;

    @Column(name = "file_count", nullable = false)
    private int fileCount = 0;

    @Column(name = "import_all_types", nullable = false)
    private boolean importAllTypes = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getPathFilter() { return pathFilter; }
    public void setPathFilter(String pathFilter) { this.pathFilter = pathFilter; }
    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }
    public boolean isAutoSync() { return autoSync; }
    public void setAutoSync(boolean autoSync) { this.autoSync = autoSync; }
    public int getSyncIntervalHours() { return syncIntervalHours; }
    public void setSyncIntervalHours(int syncIntervalHours) { this.syncIntervalHours = syncIntervalHours; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }
    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }
    public boolean isImportAllTypes() { return importAllTypes; }
    public void setImportAllTypes(boolean importAllTypes) { this.importAllTypes = importAllTypes; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
