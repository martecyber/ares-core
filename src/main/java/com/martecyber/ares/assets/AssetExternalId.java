package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Maps a source tool's own stable asset identifier (Tenable asset.id, Greenbone/GVM asset_id,
 *  CrowdStrike device_id, …) to the Ares asset it resolves to — any type — so re-imports find the
 *  same asset even when its natural key drifted (a host's active IP changed between syncs) instead
 *  of creating a duplicate. Unique per organization (V213). See V172 migration. */
@Entity
@Table(name = "asset_external_id", schema = "ares")
public class AssetExternalId {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 80)
    private String tool;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
