package com.martecyber.ares.projects;

import jakarta.persistence.*;

@Entity
@Table(name = "project_asset_access", schema = "ares")
@IdClass(ProjectAssetAccessId.class)
public class ProjectAssetAccess {

    @Id
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Id
    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /**
     * Automatic scope classification result: 'in_scope', 'out_of_scope', 'indeterminate'.
     * Written by AssetScopeClassifier. Ignored when scopeOverride=true.
     */
    @Column(name = "scope_status", nullable = false, length = 20)
    private String scopeStatus = "indeterminate";

    /**
     * When true the user has manually set scopeStatus and the classifier
     * will not overwrite it until the override is cleared.
     */
    @Column(name = "scope_override", nullable = false)
    private boolean scopeOverride = false;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getScopeStatus() { return scopeStatus; }
    public void setScopeStatus(String scopeStatus) { this.scopeStatus = scopeStatus; }

    public boolean isScopeOverride() { return scopeOverride; }
    public void setScopeOverride(boolean scopeOverride) { this.scopeOverride = scopeOverride; }
}
