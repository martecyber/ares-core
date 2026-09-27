package com.martecyber.ares.assets;

import java.io.Serializable;
import java.util.Objects;

public class ProjectRelationshipHiddenId implements Serializable {

    private Long projectId;
    private Long fromAssetId;
    private Long toAssetId;
    private String type;

    public ProjectRelationshipHiddenId() {}

    public ProjectRelationshipHiddenId(Long projectId, Long fromAssetId, Long toAssetId, String type) {
        this.projectId = projectId;
        this.fromAssetId = fromAssetId;
        this.toAssetId = toAssetId;
        this.type = type;
    }

    public Long getProjectId() { return projectId; }
    public Long getFromAssetId() { return fromAssetId; }
    public Long getToAssetId() { return toAssetId; }
    public String getType() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectRelationshipHiddenId that)) return false;
        return Objects.equals(projectId, that.projectId)
            && Objects.equals(fromAssetId, that.fromAssetId)
            && Objects.equals(toAssetId, that.toAssetId)
            && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() { return Objects.hash(projectId, fromAssetId, toAssetId, type); }
}
