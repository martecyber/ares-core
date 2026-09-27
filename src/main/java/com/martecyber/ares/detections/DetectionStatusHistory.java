package com.martecyber.ares.detections;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "detection_status_history", schema = "ares")
public class DetectionStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "detection_id", nullable = false)
    private Long detectionId;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_by_name", length = 255)
    private String changedByName;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    public DetectionStatusHistory() {}

    public DetectionStatusHistory(Long detectionId, String eventType,
                                   String fromStatus, String toStatus,
                                   Long changedBy, String changedByName,
                                   String note, OffsetDateTime changedAt) {
        this.detectionId   = detectionId;
        this.eventType     = eventType;
        this.fromStatus    = fromStatus;
        this.toStatus      = toStatus;
        this.changedBy     = changedBy;
        this.changedByName = changedByName;
        this.note          = note;
        this.changedAt     = changedAt;
    }

    public Long getId()             { return id; }
    public Long getDetectionId()    { return detectionId; }
    public String getEventType()    { return eventType; }
    public String getFromStatus()   { return fromStatus; }
    public String getToStatus()     { return toStatus; }
    public Long getChangedBy()      { return changedBy; }
    public String getChangedByName(){ return changedByName; }
    public String getNote()         { return note; }
    public OffsetDateTime getChangedAt() { return changedAt; }
}
