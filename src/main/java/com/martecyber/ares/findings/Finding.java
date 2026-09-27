package com.martecyber.ares.findings;

import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding", schema = "ares")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 15)
    private String severity = "medium";

    /** Canonical P0-P4 priority (AQL implementation plan, V144) — 0=P0 (most urgent) .. 4=P4.
     *  Null when there's no default score yet, mirroring severity's own nullable "P?" state;
     *  `severity` is derived from this at write time now, not the other way around. Short, not
     *  Integer, to match the smallint column Hibernate validates DDL types against. */
    private Short priority;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "status_id", nullable = false)
    private Long statusId;

    /** Read-only, for AQL (Phase 3) — lets a query say `status == open` instead of a raw
     *  statusId, mirroring the precedent of Detection.severityWeight. */
    @Formula("(SELECT fs.name FROM ares.finding_status fs WHERE fs.id = status_id)")
    private String statusName;

    /** Read-only, for AQL (dashboards remodel) — remediation is "open" when the finding has no
     *  affections at all, or at least one affection still open. Mirrors {@link
     *  com.martecyber.ares.findings.FindingRepository#findOpenPublishedByOrgId}'s Java-side
     *  definition exactly, just as a per-row formula so it's directly AQL-queryable instead of
     *  only reachable via that one bespoke repository query. */
    @Formula("""
        (NOT EXISTS (SELECT 1 FROM ares.affection a WHERE a.finding_id = id)
         OR EXISTS (SELECT 1 FROM ares.affection a WHERE a.finding_id = id AND a.status = 'open'))
        """)
    private boolean open;

    /** Read-only, for AQL (dashboards remodel — SLA widgets need a real queryable field instead
     *  of the Java-only computation {@code OrganizationService.deadline()} used to be the only
     *  way to get this). Mirrors that method exactly: an explicit {@code due_date} always wins;
     *  otherwise it's {@code reported_at + <severity's SLA days>}, where the day count comes from
     *  the finding's own organization's {@code sla} settings (a jsonb path keyed by severity,
     *  same shape {@code OrganizationService.parseSla} reads), falling back to the same hardcoded
     *  defaults ({@code OrganizationDto.SlaSettings.defaults()}) when unset. A severity with 0 (or
     *  no) SLA days means "no deadline" — {@code NULLIF(GREATEST(days,0),0)} turns that into NULL
     *  so the whole expression is NULL rather than "today", same as the Java version returning
     *  null. Recomputed live on every read (not materialized at write time) so it never goes
     *  stale if an org's SLA settings change later — the same reason {@code isOpen} above reads
     *  live instead of being maintained on write. */
    @Formula("""
        (COALESCE(due_date, (
            (reported_at::date + (NULLIF(GREATEST((
                SELECT COALESCE((o.settings -> 'sla' ->> severity)::int,
                    CASE severity
                        WHEN 'critical' THEN 7 WHEN 'high' THEN 30 WHEN 'medium' THEN 90
                        WHEN 'low' THEN 180 ELSE 0
                    END)
                FROM ares.project p JOIN ares.organization o ON o.id = p.organization_id
                WHERE p.id = project_id
            ), 0), 0) || ' days')::interval)
        )::date))
        """)
    private LocalDate slaDeadline;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "code", length = 70, unique = true)
    private String code;

    @Column(name = "is_draft", nullable = false)
    private boolean isDraft = true;

    @Column(name = "is_ready_to_report", nullable = false)
    private boolean isReadyToReport = false;

    @Column(name = "reported_at")
    private OffsetDateTime reportedAt;

    /** Timestamp of the transition into the terminal "resolved" status. Cleared if reopened. */
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /** Explicit resolution deadline. When null, computed from org SLA + reportedAt. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Iteration label stamped at publish time for MONITOR projects
     * (e.g. "26-W02", "26-07", "27-Q2"). Null for non-MONITOR findings.
     */
    @Column(name = "iteration_label", length = 10)
    private String iterationLabel;

    /** Custom field values, keyed by finding_field_type.name — replaces the old finding_field
     *  EAV table (AQL implementation plan, V141). Opaque JSON text, parsed/written by
     *  FindingService; the entity itself doesn't interpret it, same convention as
     *  Asset.metadata. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String fields = "{}";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Short getPriority() { return priority; }
    public void setPriority(Short priority) { this.priority = priority; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Long getStatusId() { return statusId; }
    public void setStatusId(Long statusId) { this.statusId = statusId; }

    public String getStatusName() { return statusName; }

    public boolean isOpen() { return open; }

    public LocalDate getSlaDeadline() { return slaDeadline; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public boolean isDraft() { return isDraft; }
    public void setDraft(boolean draft) { isDraft = draft; }

    public boolean isReadyToReport() { return isReadyToReport; }
    public void setReadyToReport(boolean readyToReport) { isReadyToReport = readyToReport; }

    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }

    public OffsetDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(OffsetDateTime reportedAt) { this.reportedAt = reportedAt; }

    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getIterationLabel() { return iterationLabel; }
    public void setIterationLabel(String iterationLabel) { this.iterationLabel = iterationLabel; }
}
