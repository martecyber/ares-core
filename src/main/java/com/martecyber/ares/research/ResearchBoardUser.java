package com.martecyber.ares.research;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "research_board_user", schema = "ares")
public class ResearchBoardUser {

    @EmbeddedId
    private ResearchBoardUserId id;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    public ResearchBoardUser() {}

    public ResearchBoardUser(Long boardId, Long userId) {
        this.id = new ResearchBoardUserId(boardId, userId);
        this.addedAt = OffsetDateTime.now();
    }

    public ResearchBoardUserId getId() { return id; }
    public void setId(ResearchBoardUserId id) { this.id = id; }

    public OffsetDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(OffsetDateTime addedAt) { this.addedAt = addedAt; }
}
