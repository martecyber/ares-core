package com.martecyber.ares.projects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Embeddable
public class ProjectStatusHistoryId implements Serializable {

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "status_id")
    private Long statusId;

    @Column(name = "changed_at")
    private OffsetDateTime changedAt;

    public ProjectStatusHistoryId() {}

    public ProjectStatusHistoryId(Long projectId, Long statusId, OffsetDateTime changedAt) {
        this.projectId = projectId;
        this.statusId = statusId;
        this.changedAt = changedAt;
    }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getStatusId() { return statusId; }
    public void setStatusId(Long statusId) { this.statusId = statusId; }

    public OffsetDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(OffsetDateTime changedAt) { this.changedAt = changedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectStatusHistoryId that)) return false;
        return Objects.equals(projectId, that.projectId) &&
               Objects.equals(statusId, that.statusId) &&
               Objects.equals(changedAt, that.changedAt);
    }

    @Override
    public int hashCode() { return Objects.hash(projectId, statusId, changedAt); }
}
