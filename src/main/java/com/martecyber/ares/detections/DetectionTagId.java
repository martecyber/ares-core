package com.martecyber.ares.detections;

import java.io.Serializable;
import java.util.Objects;

public class DetectionTagId implements Serializable {
    private Long detectionId;
    private Long tagId;

    public DetectionTagId() {}
    public DetectionTagId(Long detectionId, Long tagId) {
        this.detectionId = detectionId;
        this.tagId = tagId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DetectionTagId that)) return false;
        return Objects.equals(detectionId, that.detectionId) && Objects.equals(tagId, that.tagId);
    }
    @Override public int hashCode() { return Objects.hash(detectionId, tagId); }
}
