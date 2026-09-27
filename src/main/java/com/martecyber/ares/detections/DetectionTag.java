package com.martecyber.ares.detections;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "detection_tag", schema = "ares")
@IdClass(DetectionTagId.class)
public class DetectionTag {

    @Id
    @Column(name = "detection_id")
    private Long detectionId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getDetectionId() { return detectionId; }
    public void setDetectionId(Long detectionId) { this.detectionId = detectionId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
