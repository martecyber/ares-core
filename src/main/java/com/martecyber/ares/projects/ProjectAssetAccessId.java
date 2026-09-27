package com.martecyber.ares.projects;

import java.io.Serializable;
import java.util.Objects;

public class ProjectAssetAccessId implements Serializable {

    private Long projectId;
    private Long assetId;

    public ProjectAssetAccessId() {}

    public ProjectAssetAccessId(Long projectId, Long assetId) {
        this.projectId = projectId;
        this.assetId = assetId;
    }

    public Long getProjectId() { return projectId; }
    public Long getAssetId() { return assetId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectAssetAccessId that)) return false;
        return Objects.equals(projectId, that.projectId)
            && Objects.equals(assetId, that.assetId);
    }

    @Override
    public int hashCode() { return Objects.hash(projectId, assetId); }
}
