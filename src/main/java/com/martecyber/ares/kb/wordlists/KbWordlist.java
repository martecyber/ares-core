package com.martecyber.ares.kb.wordlists;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kb_wordlist", schema = "ares")
public class KbWordlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folder_id")
    private Long folderId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "line_count")
    private Long lineCount;

    @Column(name = "source_repo_id")
    private Long sourceRepoId;

    @Column(name = "source_repo_path", length = 1024)
    private String sourceRepoPath;

    @Column(name = "source_repo_sha1", length = 40)
    private String sourceRepoSha1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public Long getLineCount() { return lineCount; }
    public void setLineCount(Long lineCount) { this.lineCount = lineCount; }

    public Long getSourceRepoId() { return sourceRepoId; }
    public void setSourceRepoId(Long sourceRepoId) { this.sourceRepoId = sourceRepoId; }
    public String getSourceRepoPath() { return sourceRepoPath; }
    public void setSourceRepoPath(String sourceRepoPath) { this.sourceRepoPath = sourceRepoPath; }
    public String getSourceRepoSha1() { return sourceRepoSha1; }
    public void setSourceRepoSha1(String sourceRepoSha1) { this.sourceRepoSha1 = sourceRepoSha1; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
