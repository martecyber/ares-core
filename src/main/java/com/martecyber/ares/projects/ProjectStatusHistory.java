package com.martecyber.ares.projects;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project_status_history", schema = "ares")
public class ProjectStatusHistory {

    @EmbeddedId
    private ProjectStatusHistoryId id;

    public ProjectStatusHistory() {}

    public ProjectStatusHistory(Long projectId, Long statusId, OffsetDateTime changedAt) {
        this.id = new ProjectStatusHistoryId(projectId, statusId, changedAt);
    }

    public ProjectStatusHistoryId getId() { return id; }
    public void setId(ProjectStatusHistoryId id) { this.id = id; }
}
