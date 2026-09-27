package com.martecyber.ares.projects.testing;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A testing guide assigned to a project. The guide name is snapshotted so the
 * checklist is stable; {@code guideId} is kept (nullable) so the assignment can be
 * re-synced against the live KB guide.
 */
@Entity
@Table(name = "project_testing_guide", schema = "ares")
public class ProjectTestingGuide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "guide_id")
    private Long guideId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }

    public Long getGuideId() { return guideId; }
    public void setGuideId(Long v) { this.guideId = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(OffsetDateTime v) { this.assignedAt = v; }
}
