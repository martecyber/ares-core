package com.martecyber.ares.findings.templates;

import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding_template", schema = "ares")
public class FindingTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String severity;

    /** Read-only bridge to Ares's canonical P0-P4 scale — FindingTemplate has no real priority
     *  column of its own (V144 deliberately left this entity untouched), so AQL's PRIORITY-typed
     *  query surface reads this formula instead. Mapping mirrors
     *  {@link com.martecyber.ares.common.PriorityThresholds#fromSeverityName}. */
    @Formula("(CASE severity " +
        "WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END)")
    private Short priority;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Short getPriority() { return priority; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
