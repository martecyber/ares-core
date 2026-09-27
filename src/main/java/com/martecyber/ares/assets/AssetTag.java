package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "asset_tag", schema = "ares")
@IdClass(AssetTagId.class)
public class AssetTag {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @Id
    @Column(name = "tag_id")
    private Long tagId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
