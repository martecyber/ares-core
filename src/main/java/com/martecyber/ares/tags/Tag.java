package com.martecyber.ares.tags;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tag", schema = "ares")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means a platform tag — see {@link TagService} for the visibility/mutation rules. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String name;

    /** Hex color, e.g. "#4F46E5" — background swatch; UI derives black/white text for contrast. */
    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
