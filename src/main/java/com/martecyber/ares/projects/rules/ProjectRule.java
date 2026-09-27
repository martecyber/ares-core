package com.martecyber.ares.projects.rules;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project_rule", schema = "ares")
public class ProjectRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "rule_type", nullable = false, length = 50)
    private String ruleType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "note")
    private String note;

    /** Non-null when this rule was auto-populated from an external platform's rules of
     *  engagement (e.g. "intigriti") rather than created manually — lets a re-sync find and
     *  update/remove its own rows without ever touching a manually-created rule of the same type. */
    @Column(name = "synced_from", length = 20)
    private String syncedFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String config = "{}";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getSyncedFrom() { return syncedFrom; }
    public void setSyncedFrom(String syncedFrom) { this.syncedFrom = syncedFrom; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
