package com.martecyber.ares.assets;

import java.io.Serializable;
import java.util.Objects;

public class AssetRelationshipId implements Serializable {

    private Long fromAssetId;
    private Long toAssetId;
    private String type;

    public AssetRelationshipId() {}

    public AssetRelationshipId(Long fromAssetId, Long toAssetId, String type) {
        this.fromAssetId = fromAssetId;
        this.toAssetId = toAssetId;
        this.type = type;
    }

    public Long getFromAssetId() { return fromAssetId; }
    public Long getToAssetId() { return toAssetId; }
    public String getType() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssetRelationshipId that)) return false;
        return Objects.equals(fromAssetId, that.fromAssetId)
            && Objects.equals(toAssetId, that.toAssetId)
            && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() { return Objects.hash(fromAssetId, toAssetId, type); }
}
