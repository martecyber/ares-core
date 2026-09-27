package com.martecyber.ares.research;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Kept after removal/board archive (removedAt set) so the archived tab can still show which
 *  detections a resolved board covered — see the table's own migration comment. */
@Entity
@Table(name = "research_board_detection", schema = "ares")
public class ResearchBoardDetection {

    @EmbeddedId
    private ResearchBoardDetectionId id;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    public ResearchBoardDetection() {}

    public ResearchBoardDetection(Long boardId, Long detectionId) {
        this.id = new ResearchBoardDetectionId(boardId, detectionId);
        this.addedAt = OffsetDateTime.now();
    }

    public ResearchBoardDetectionId getId() { return id; }
    public void setId(ResearchBoardDetectionId id) { this.id = id; }

    public OffsetDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(OffsetDateTime addedAt) { this.addedAt = addedAt; }

    public OffsetDateTime getRemovedAt() { return removedAt; }
    public void setRemovedAt(OffsetDateTime removedAt) { this.removedAt = removedAt; }
}
