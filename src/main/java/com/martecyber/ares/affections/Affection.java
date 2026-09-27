package com.martecyber.ares.affections;

import com.martecyber.ares.detections.Detection;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "affection", schema = "ares")
public class Affection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(nullable = false, length = 80, unique = true)
    private String code;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Remediation status: 'open' | 'closed'. Recomputed whenever affect statuses change. */
    @Column(name = "status", length = 10, nullable = false)
    private String status = "open";

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        schema = "ares",
        name = "affection_detection",
        joinColumns = @JoinColumn(name = "affection_id"),
        inverseJoinColumns = @JoinColumn(name = "detection_id")
    )
    private Set<Detection> detections = new HashSet<>();

    // insertable/updatable = false: AffectionAsset.affectionId is itself a plain @Id @Column
    // (part of its composite id, set directly by its own constructor — see addAssetLink below),
    // not something Hibernate should ALSO think it owns via this collection's join column.
    // Without this, deleting the owning Affection makes Hibernate first UPDATE every child row's
    // affection_id to NULL (its default "disassociate" step for a unidirectional @OneToMany +
    // @JoinColumn, regardless of orphanRemoval) before cascading the actual DELETE — which fails
    // outright since affection_id is NOT NULL. Marking the join column read-only here tells
    // Hibernate the FK is already reliably written by the child's own id, so cascade-delete can
    // just remove each orphan by its primary key without ever touching the column first.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "affection_id", insertable = false, updatable = false)
    private List<AffectionAsset> assetLinks = new ArrayList<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Set<Detection> getDetections() { return detections; }

    public List<AffectionAsset> getAssetLinks() { return assetLinks; }

    // ── Convenience helpers ───────────────────────────────────────────────────

    public String getStatus()         { return status; }
    public void setStatus(String s)   { this.status = s; }

    public void addAssetLink(Long assetId, String role, java.time.OffsetDateTime observedAt) {
        boolean exists = assetLinks.stream()
            .anyMatch(l -> l.getAssetId().equals(assetId) && l.getRole().equals(role));
        if (!exists) assetLinks.add(new AffectionAsset(id, assetId, role, observedAt));
    }

    public void removeAssetLink(Long assetId, String role) {
        assetLinks.removeIf(l -> l.getAssetId().equals(assetId) && l.getRole().equals(role));
    }
}
