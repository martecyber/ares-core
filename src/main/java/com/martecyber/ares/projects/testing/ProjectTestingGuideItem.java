package com.martecyber.ares.projects.testing;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One checklist item within an assigned guide. Title/description are snapshotted
 * from the KB point; {@code guidePointId} (nullable) links back for procedures +
 * re-sync. Status is one of pending|done|not_applicable.
 */
@Entity
@Table(name = "project_testing_guide_item", schema = "ares")
public class ProjectTestingGuideItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_testing_guide_id", nullable = false)
    private Long projectTestingGuideId;

    @Column(name = "guide_point_id")
    private Long guidePointId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public Long getId() { return id; }

    public Long getProjectTestingGuideId() { return projectTestingGuideId; }
    public void setProjectTestingGuideId(Long v) { this.projectTestingGuideId = v; }

    public Long getGuidePointId() { return guidePointId; }
    public void setGuidePointId(Long v) { this.guidePointId = v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long v) { this.updatedBy = v; }
}
