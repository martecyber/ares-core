package com.martecyber.ares.projects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project_scope_entry", schema = "ares")
public class ProjectScopeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 50)
    private String kind;

    @Column(nullable = false, length = 255)
    private String value;

    @Column(columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "in_scope", nullable = false)
    private boolean inScope = true;

    /** 'manual' or the platform name: 'bugcrowd', 'yeswehack', 'intigriti', 'hackerone'. */
    @Column(nullable = false, length = 50)
    private String source = "manual";

    /** External target/scope ID on the originating platform; null for manual entries. */
    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Timestamp from the originating platform; null for manual entries. */
    @Column(name = "platform_created_at")
    private OffsetDateTime platformCreatedAt;

    /** Timestamp from the originating platform; null for manual entries. */
    @Column(name = "platform_updated_at")
    private OffsetDateTime platformUpdatedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public boolean isInScope() { return inScope; }
    public void setInScope(boolean inScope) { this.inScope = inScope; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public OffsetDateTime getPlatformCreatedAt() { return platformCreatedAt; }
    public void setPlatformCreatedAt(OffsetDateTime platformCreatedAt) { this.platformCreatedAt = platformCreatedAt; }

    public OffsetDateTime getPlatformUpdatedAt() { return platformUpdatedAt; }
    public void setPlatformUpdatedAt(OffsetDateTime platformUpdatedAt) { this.platformUpdatedAt = platformUpdatedAt; }
}
