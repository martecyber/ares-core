package com.martecyber.ares.detections;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import java.time.OffsetDateTime;

@Entity
@Table(name = "detection", schema = "ares")
public class Detection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(nullable = false, length = 15)
    private String severity;

    /** Canonical P0-P4 priority (AQL implementation plan, V144) — 0=P0 (most urgent) .. 4=P4.
     *  The stored source of truth; `severity` is derived from it at write time now, not the
     *  other way around. Short, not Integer, to match the smallint column Hibernate validates
     *  DDL types against. */
    @Column(nullable = false)
    private Short priority;

    @Column(nullable = false, length = 20)
    private String status;

    /** Relational source of truth for transition/escalation validation (AQL implementation
     *  plan, V143). `status` (above) stays the wire format the frontend/CLI use, kept in sync
     *  by DetectionService on every write. */
    @Column(name = "status_id", nullable = false)
    private Long statusId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    private String rawData;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_template_id", length = 200)
    private String sourceTemplateId;

    @Column(name = "dedup_hash", length = 64)
    private String dedupHash;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        schema = "ares",
        name = "reference_entry_detection",
        joinColumns = @JoinColumn(name = "detection_id"),
        inverseJoinColumns = @JoinColumn(name = "reference_entry_id")
    )
    private java.util.Set<com.martecyber.ares.references.ReferenceEntry> references = new java.util.HashSet<>();

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "detection_id")
    private java.util.List<DetectionScore> scores = new java.util.ArrayList<>();

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Short getPriority() { return priority; }
    public void setPriority(Short priority) { this.priority = priority; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getStatusId() { return statusId; }
    public void setStatusId(Long statusId) { this.statusId = statusId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(String sourceTemplateId) { this.sourceTemplateId = sourceTemplateId; }

    public String getDedupHash() { return dedupHash; }
    public void setDedupHash(String dedupHash) { this.dedupHash = dedupHash; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public OffsetDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(OffsetDateTime lastSeen) { this.lastSeen = lastSeen; }

    public java.util.Set<com.martecyber.ares.references.ReferenceEntry> getReferences() { return references; }
    public java.util.List<DetectionScore> getScores() { return scores; }
}
