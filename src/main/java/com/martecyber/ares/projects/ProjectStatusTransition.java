package com.martecyber.ares.projects;

import jakarta.persistence.*;

@Entity
@Table(name = "project_status_transition", schema = "ares")
public class ProjectStatusTransition {

    @EmbeddedId
    private ProjectStatusTransitionId id;

    @Column(length = 50)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    public ProjectStatusTransitionId getId() { return id; }
    public void setId(ProjectStatusTransitionId id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
