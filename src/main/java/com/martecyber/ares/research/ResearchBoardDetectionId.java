package com.martecyber.ares.research;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ResearchBoardDetectionId implements Serializable {

    @Column(name = "board_id")
    private Long boardId;

    @Column(name = "detection_id")
    private Long detectionId;

    public ResearchBoardDetectionId() {}

    public ResearchBoardDetectionId(Long boardId, Long detectionId) {
        this.boardId = boardId;
        this.detectionId = detectionId;
    }

    public Long getBoardId() { return boardId; }
    public void setBoardId(Long boardId) { this.boardId = boardId; }

    public Long getDetectionId() { return detectionId; }
    public void setDetectionId(Long detectionId) { this.detectionId = detectionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResearchBoardDetectionId that)) return false;
        return Objects.equals(boardId, that.boardId) && Objects.equals(detectionId, that.detectionId);
    }

    @Override
    public int hashCode() { return Objects.hash(boardId, detectionId); }
}
