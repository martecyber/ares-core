package com.martecyber.ares.research;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "research_board", schema = "ares")
public class ResearchBoard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "lead_user_id")
    private Long leadUserId;

    /** "active" | "archived". */
    @Column(nullable = false, length = 10)
    private String status = "active";

    /** "affected" | "not_affected" — set only when status="archived". */
    @Column(length = 20)
    private String verdict;

    /** Set only when verdict="affected". */
    @Column(name = "result_affection_id")
    private Long resultAffectionId;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Long getLeadUserId() { return leadUserId; }
    public void setLeadUserId(Long leadUserId) { this.leadUserId = leadUserId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public Long getResultAffectionId() { return resultAffectionId; }
    public void setResultAffectionId(Long resultAffectionId) { this.resultAffectionId = resultAffectionId; }

    public OffsetDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(OffsetDateTime archivedAt) { this.archivedAt = archivedAt; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
