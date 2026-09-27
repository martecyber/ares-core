package com.martecyber.ares.findings;

import jakarta.persistence.*;

@Entity
@Table(name = "finding_status_transition", schema = "ares")
public class FindingStatusTransition {

    @EmbeddedId
    private FindingStatusTransitionId id;

    @Column(length = 50)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    public FindingStatusTransitionId getId() { return id; }
    public void setId(FindingStatusTransitionId id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
