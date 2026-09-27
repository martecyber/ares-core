package com.martecyber.ares.aql.materialize;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kb_materialized_ref", schema = "ares")
public class KbMaterializedRef {

    @EmbeddedId
    private KbMaterializedRefId id;

    @Column(name = "kev_listed")
    private Boolean kevListed;

    @Column(name = "cvss_score")
    private BigDecimal cvssScore;

    @Column(length = 15)
    private String severity;

    @Column(name = "exploit_count")
    private Integer exploitCount;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;

    public KbMaterializedRefId getId() { return id; }
    public void setId(KbMaterializedRefId id) { this.id = id; }

    public Boolean getKevListed() { return kevListed; }
    public void setKevListed(Boolean kevListed) { this.kevListed = kevListed; }

    public BigDecimal getCvssScore() { return cvssScore; }
    public void setCvssScore(BigDecimal cvssScore) { this.cvssScore = cvssScore; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Integer getExploitCount() { return exploitCount; }
    public void setExploitCount(Integer exploitCount) { this.exploitCount = exploitCount; }

    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
}
