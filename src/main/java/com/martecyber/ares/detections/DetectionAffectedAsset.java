package com.martecyber.ares.detections;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Join row linking a {@link Detection} to an asset the operator marks as
 * "affected" by the finding. Independent of {@code detection.asset_id} (the
 * single asset where the scanner actually observed the issue) so the impact
 * can be widened to upstream/related assets of any type.
 *
 * Default after a fresh import: one row where {@code asset_id} equals the
 * detection's own {@code asset_id} — see V79 backfill and {@code ImportService}.
 */
@Entity
@Table(name = "detection_affected_asset", schema = "ares")
@IdClass(DetectionAffectedAsset.Key.class)
public class DetectionAffectedAsset {

    @Id
    @Column(name = "detection_id", nullable = false)
    private Long detectionId;

    @Id
    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    public DetectionAffectedAsset() {}
    public DetectionAffectedAsset(Long detectionId, Long assetId) {
        this.detectionId = detectionId;
        this.assetId = assetId;
    }

    public Long getDetectionId() { return detectionId; }
    public void setDetectionId(Long v) { this.detectionId = v; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long v) { this.assetId = v; }

    public static class Key implements Serializable {
        private Long detectionId;
        private Long assetId;

        public Key() {}
        public Key(Long detectionId, Long assetId) {
            this.detectionId = detectionId;
            this.assetId = assetId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(detectionId, k.detectionId)
                && Objects.equals(assetId, k.assetId);
        }
        @Override public int hashCode() { return Objects.hash(detectionId, assetId); }
    }
}
