package com.martecyber.ares.findings;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding_score", schema = "ares")
public class FindingScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(nullable = false, precision = 3, scale = 1)
    private BigDecimal score;

    @Column(columnDefinition = "text")
    private String vector;

    /** Authoritative link to the SSVC decision-tree leaf this score resolved to, when
     *  scored via com.martecyber.ares.priorization.ssvc. Null for CVSS/Manual scores. */
    @Column(name = "ssvc_leaf_node_id")
    private Long ssvcLeafNodeId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(columnDefinition = "text")
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }

    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }

    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }

    public String getVector() { return vector; }
    public void setVector(String vector) { this.vector = vector; }

    public Long getSsvcLeafNodeId() { return ssvcLeafNodeId; }
    public void setSsvcLeafNodeId(Long ssvcLeafNodeId) { this.ssvcLeafNodeId = ssvcLeafNodeId; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = (comment != null && comment.isBlank()) ? null : comment; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
