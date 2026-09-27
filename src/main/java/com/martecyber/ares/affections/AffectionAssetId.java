package com.martecyber.ares.affections;

import java.io.Serializable;
import java.util.Objects;

public class AffectionAssetId implements Serializable {
    private Long affectionId;
    private Long assetId;
    private String role;

    public AffectionAssetId() {}
    public AffectionAssetId(Long affectionId, Long assetId, String role) {
        this.affectionId = affectionId;
        this.assetId = assetId;
        this.role = role;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AffectionAssetId that)) return false;
        return Objects.equals(affectionId, that.affectionId)
            && Objects.equals(assetId, that.assetId)
            && Objects.equals(role, that.role);
    }
    @Override public int hashCode() { return Objects.hash(affectionId, assetId, role); }
}
