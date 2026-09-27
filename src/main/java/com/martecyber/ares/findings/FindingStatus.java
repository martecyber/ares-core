package com.martecyber.ares.findings;

import jakarta.persistence.*;

@Entity
@Table(name = "finding_status", schema = "ares")
public class FindingStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "means_closed", nullable = false)
    private boolean meansClosed;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isMeansClosed() { return meansClosed; }
    public void setMeansClosed(boolean meansClosed) { this.meansClosed = meansClosed; }
}
