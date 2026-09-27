package com.martecyber.ares.affections;

import com.martecyber.ares.assets.Asset;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "affection_asset", schema = "ares")
@IdClass(AffectionAssetId.class)
public class AffectionAsset {

    @Id
    @Column(name = "affection_id", nullable = false)
    private Long affectionId;

    @Id
    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Id
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /** When the asset was first observed with this issue (only set for role='detected_at'). */
    @Column(name = "observed_at")
    private OffsetDateTime observedAt;

    /** Remediation status of this affected asset (only set for role='affects'). */
    @Column(name = "status", length = 20)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", insertable = false, updatable = false)
    private Asset asset;

    public AffectionAsset() {}

    public AffectionAsset(Long affectionId, Long assetId, String role, OffsetDateTime observedAt) {
        this.affectionId = affectionId;
        this.assetId     = assetId;
        this.role        = role;
        this.observedAt  = "detected_at".equals(role) ? observedAt : null;
        this.status      = "affects".equals(role) ? "open" : null;
    }

    public Long getAffectionId()       { return affectionId; }
    public Long getAssetId()           { return assetId; }
    public String getRole()            { return role; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public String getStatus()          { return status; }
    public void setStatus(String s)    { this.status = s; }
    public Asset getAsset()            { return asset; }
}
