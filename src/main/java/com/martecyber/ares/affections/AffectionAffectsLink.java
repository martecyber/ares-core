package com.martecyber.ares.affections;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Per-affection link declaring that a specific detected_at asset is the source
 * of a specific affects asset. Lets operators model "detected here → affected
 * these others" with N-to-M cardinality within a single affection.
 *
 * The legacy {@code affection_asset} rows with {@code role='affects'} are
 * derived from this table by {@code AffectionService}: they exist when at
 * least one link references them, and are removed when the last link is.
 */
@Entity
@Table(name = "affection_affects_link", schema = "ares")
@IdClass(AffectionAffectsLink.Key.class)
public class AffectionAffectsLink {

    @Id
    @Column(name = "affection_id", nullable = false)
    private Long affectionId;

    @Id
    @Column(name = "detected_asset_id", nullable = false)
    private Long detectedAssetId;

    @Id
    @Column(name = "affects_asset_id", nullable = false)
    private Long affectsAssetId;

    public AffectionAffectsLink() {}
    public AffectionAffectsLink(Long affectionId, Long detectedAssetId, Long affectsAssetId) {
        this.affectionId = affectionId;
        this.detectedAssetId = detectedAssetId;
        this.affectsAssetId = affectsAssetId;
    }

    public Long getAffectionId() { return affectionId; }
    public void setAffectionId(Long v) { this.affectionId = v; }
    public Long getDetectedAssetId() { return detectedAssetId; }
    public void setDetectedAssetId(Long v) { this.detectedAssetId = v; }
    public Long getAffectsAssetId() { return affectsAssetId; }
    public void setAffectsAssetId(Long v) { this.affectsAssetId = v; }

    public static class Key implements Serializable {
        private Long affectionId;
        private Long detectedAssetId;
        private Long affectsAssetId;

        public Key() {}
        public Key(Long affectionId, Long detectedAssetId, Long affectsAssetId) {
            this.affectionId = affectionId;
            this.detectedAssetId = detectedAssetId;
            this.affectsAssetId = affectsAssetId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(affectionId, k.affectionId)
                && Objects.equals(detectedAssetId, k.detectedAssetId)
                && Objects.equals(affectsAssetId, k.affectsAssetId);
        }
        @Override public int hashCode() { return Objects.hash(affectionId, detectedAssetId, affectsAssetId); }
    }
}
