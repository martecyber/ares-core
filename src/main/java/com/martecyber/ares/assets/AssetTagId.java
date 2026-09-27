package com.martecyber.ares.assets;

import java.io.Serializable;
import java.util.Objects;

public class AssetTagId implements Serializable {
    private Long assetId;
    private Long tagId;

    public AssetTagId() {}
    public AssetTagId(Long assetId, Long tagId) {
        this.assetId = assetId;
        this.tagId = tagId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssetTagId that)) return false;
        return Objects.equals(assetId, that.assetId) && Objects.equals(tagId, that.tagId);
    }
    @Override public int hashCode() { return Objects.hash(assetId, tagId); }
}
