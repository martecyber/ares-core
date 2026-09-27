package com.martecyber.ares.kb.testing;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** A single test within a {@link TestingGuide}: title + description. */
@Entity
@Table(name = "testing_guide_point", schema = "ares")
public class TestingGuidePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guide_id", nullable = false)
    private Long guideId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getGuideId() { return guideId; }
    public void setGuideId(Long v) { this.guideId = v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
