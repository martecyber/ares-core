package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Presence of a row means the (fromAssetId, toAssetId, type) relationship is hidden from
 *  that project's graph view — the underlying (org-wide) AssetRelationship row is untouched. */
@Entity
@Table(name = "project_relationship_hidden", schema = "ares")
@IdClass(ProjectRelationshipHiddenId.class)
public class ProjectRelationshipHidden {

    @Id
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Id
    @Column(name = "from_asset_id", nullable = false)
    private Long fromAssetId;

    @Id
    @Column(name = "to_asset_id", nullable = false)
    private Long toAssetId;

    @Id
    @Column(nullable = false, length = 255)
    private String type;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getFromAssetId() { return fromAssetId; }
    public void setFromAssetId(Long fromAssetId) { this.fromAssetId = fromAssetId; }

    public Long getToAssetId() { return toAssetId; }
    public void setToAssetId(Long toAssetId) { this.toAssetId = toAssetId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
