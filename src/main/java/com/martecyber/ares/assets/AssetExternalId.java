package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Maps a source tool's own stable host identifier (Tenable asset.id, Greenbone/GVM asset_id, …)
 *  to the Ares HOST asset it resolves to, so re-imports find the same host even when the
 *  IP/interface-based resolution in {@code AssetImportHelper} would otherwise miss (active IP
 *  changed between syncs) and create a duplicate. See V172 migration. */
@Entity
@Table(name = "asset_external_id", schema = "ares")
public class AssetExternalId {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(nullable = false, length = 80)
    private String tool;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
