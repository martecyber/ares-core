package com.martecyber.ares.projects;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project_iteration", schema = "ares")
public class ProjectIteration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 10)
    private String label;

    @Column(name = "planned_start", nullable = false)
    private LocalDate plannedStart;

    @Column(name = "planned_end", nullable = false)
    private LocalDate plannedEnd;

    @Column(name = "actual_start")
    private LocalDate actualStart;

    @Column(name = "actual_end")
    private LocalDate actualEnd;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    public ProjectIteration() {}

    public ProjectIteration(Long projectId, String label, LocalDate plannedStart, LocalDate plannedEnd,
                            LocalDate actualStart) {
        this.projectId = projectId;
        this.label = label;
        this.plannedStart = plannedStart;
        this.plannedEnd = plannedEnd;
        this.actualStart = actualStart;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getLabel() { return label; }

    public LocalDate getPlannedStart() { return plannedStart; }
    public void setPlannedStart(LocalDate plannedStart) { this.plannedStart = plannedStart; }

    public LocalDate getPlannedEnd() { return plannedEnd; }
    public void setPlannedEnd(LocalDate plannedEnd) { this.plannedEnd = plannedEnd; }

    public LocalDate getActualStart() { return actualStart; }
    public void setActualStart(LocalDate actualStart) { this.actualStart = actualStart; }

    public LocalDate getActualEnd() { return actualEnd; }
    public void setActualEnd(LocalDate actualEnd) { this.actualEnd = actualEnd; }

    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDate effectiveEnd() {
        if (actualEnd != null) return actualEnd;
        LocalDate today = LocalDate.now();
        return plannedEnd.isBefore(today) ? today : plannedEnd;
    }
}
