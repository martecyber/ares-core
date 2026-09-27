package com.martecyber.ares.detections;

import jakarta.persistence.*;

@Entity
@Table(name = "detection_status", schema = "ares")
public class DetectionStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "means_closed", nullable = false)
    private boolean meansClosed;

    /** Gates the escalate() action (create/link a Finding) — not a status transition. */
    @Column(nullable = false)
    private boolean escalatable;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isMeansClosed() { return meansClosed; }
    public void setMeansClosed(boolean meansClosed) { this.meansClosed = meansClosed; }

    public boolean isEscalatable() { return escalatable; }
    public void setEscalatable(boolean escalatable) { this.escalatable = escalatable; }
}
