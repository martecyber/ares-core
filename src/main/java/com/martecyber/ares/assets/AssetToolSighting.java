package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "asset_tool_sighting", schema = "ares")
public class AssetToolSighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** Which project's sync/import produced this sighting — null only for legacy pre-V161 rows
     *  (see that migration's own comment), never for a row created going forward. Asset itself is
     *  organization-scoped and can be linked to several different projects within an org, so this
     *  is what keeps one project's tool provenance from leaking into another's. */
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String tool;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    public Long getId() { return id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime v) { this.firstSeenAt = v; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime v) { this.lastSeenAt = v; }
}
