package com.martecyber.ares.affections;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "affect_status_history", schema = "ares")
public class AffectStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "affection_id", nullable = false)
    private Long affectionId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_by_name", length = 255)
    private String changedByName;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    public AffectStatusHistory() {}

    public AffectStatusHistory(Long affectionId, Long assetId,
                                String fromStatus, String toStatus,
                                Long changedBy, String changedByName,
                                String note, OffsetDateTime changedAt) {
        this.affectionId   = affectionId;
        this.assetId       = assetId;
        this.fromStatus    = fromStatus;
        this.toStatus      = toStatus;
        this.changedBy     = changedBy;
        this.changedByName = changedByName;
        this.note          = note;
        this.changedAt     = changedAt;
    }

    public Long getId()             { return id; }
    public Long getAffectionId()    { return affectionId; }
    public Long getAssetId()        { return assetId; }
    public String getFromStatus()   { return fromStatus; }
    public String getToStatus()     { return toStatus; }
    public Long getChangedBy()      { return changedBy; }
    public String getChangedByName(){ return changedByName; }
    public String getNote()         { return note; }
    public OffsetDateTime getChangedAt() { return changedAt; }
}
