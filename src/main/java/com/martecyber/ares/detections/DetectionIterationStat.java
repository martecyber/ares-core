package com.martecyber.ares.detections;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * One row per (project, detection, iteration_label, area) the first time a detection enters
 * that area during that iteration — see the migration comment (V122) for the full semantics,
 * in particular that a detection can legitimately have rows in more than one area for the same
 * iteration (e.g. opened then closed within the same period).
 */
@Entity
@Table(name = "detection_iteration_stat", schema = "ares")
public class DetectionIterationStat {

    public static final String AREA_OPEN = "open";
    public static final String AREA_ESCALATED = "escalated";
    public static final String AREA_CLOSED = "closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "detection_id", nullable = false)
    private Long detectionId;

    @Column(name = "iteration_label", nullable = false, length = 10)
    private String iterationLabel;

    @Column(nullable = false, length = 20)
    private String area;

    @Column(nullable = false, length = 15)
    private String severity;

    @Column(name = "entered_at", nullable = false)
    private OffsetDateTime enteredAt;

    public DetectionIterationStat() {}

    public DetectionIterationStat(Long projectId, Long detectionId, String iterationLabel,
                                   String area, String severity, OffsetDateTime enteredAt) {
        this.projectId = projectId;
        this.detectionId = detectionId;
        this.iterationLabel = iterationLabel;
        this.area = area;
        this.severity = severity;
        this.enteredAt = enteredAt;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getDetectionId() { return detectionId; }
    public String getIterationLabel() { return iterationLabel; }
    public String getArea() { return area; }
    public String getSeverity() { return severity; }
    public OffsetDateTime getEnteredAt() { return enteredAt; }
}
