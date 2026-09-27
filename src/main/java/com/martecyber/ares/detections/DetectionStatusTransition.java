package com.martecyber.ares.detections;

import jakarta.persistence.*;

@Entity
@Table(name = "detection_status_transition", schema = "ares")
public class DetectionStatusTransition {

    @EmbeddedId
    private DetectionStatusTransitionId id;

    public DetectionStatusTransitionId getId() { return id; }
    public void setId(DetectionStatusTransitionId id) { this.id = id; }
}
