package com.martecyber.ares.imports;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row per asset/detection an import touched — enough to roll the import back later.
 * 'created' rows have no {@code prevValues} (rollback = delete); 'updated' rows snapshot
 * only the fields the import was about to overwrite, captured just before the mutation.
 */
@Entity
@Table(name = "scan_import_change", schema = "ares")
public class ScanImportChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_import_id", nullable = false)
    private Long scanImportId;

    /** 'asset' | 'detection' */
    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** 'created' | 'updated' */
    @Column(nullable = false, length = 10)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prev_values", columnDefinition = "jsonb")
    private String prevValues;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reverted_at")
    private OffsetDateTime revertedAt;

    public Long getId() { return id; }

    public Long getScanImportId() { return scanImportId; }
    public void setScanImportId(Long scanImportId) { this.scanImportId = scanImportId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getPrevValues() { return prevValues; }
    public void setPrevValues(String prevValues) { this.prevValues = prevValues; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getRevertedAt() { return revertedAt; }
    public void setRevertedAt(OffsetDateTime revertedAt) { this.revertedAt = revertedAt; }
}
