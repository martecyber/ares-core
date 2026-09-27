package com.martecyber.ares.projects;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project_member", schema = "ares")
public class ProjectMember {

    @EmbeddedId
    private ProjectMemberId id;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    public ProjectMember() {}

    public ProjectMember(Long projectId, Long userId, String role) {
        this.id = new ProjectMemberId(projectId, userId, role);
        this.addedAt = OffsetDateTime.now();
    }

    public ProjectMemberId getId() { return id; }
    public void setId(ProjectMemberId id) { this.id = id; }

    public String getRole() { return id.getRole(); }

    public OffsetDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(OffsetDateTime addedAt) { this.addedAt = addedAt; }
}
