package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "asset_relationships", schema = "ares")
@IdClass(AssetRelationshipId.class)
public class AssetRelationship {

    @Id
    @Column(name = "from_asset_id", nullable = false)
    private Long fromAssetId;

    @Id
    @Column(name = "to_asset_id", nullable = false)
    private Long toAssetId;

    @Id
    @Column(nullable = false, length = 255)
    private String type;

    @Column(nullable = false)
    private boolean directional = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getFromAssetId() { return fromAssetId; }
    public void setFromAssetId(Long fromAssetId) { this.fromAssetId = fromAssetId; }

    public Long getToAssetId() { return toAssetId; }
    public void setToAssetId(Long toAssetId) { this.toAssetId = toAssetId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isDirectional() { return directional; }
    public void setDirectional(boolean directional) { this.directional = directional; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
