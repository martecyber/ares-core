package com.martecyber.ares.findings.templates;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "finding_template_score", schema = "ares")
public class FindingTemplateScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(nullable = false, precision = 3, scale = 1)
    private BigDecimal score;

    /** A plain CVSS vector / SSVC breadcrumb string — same TEXT storage FindingScore uses
     *  for its equivalent `vector` column, not a JSON document (there's no auto JSON
     *  encode/decode on a @JdbcTypeCode(SqlTypes.JSON) String field — Hibernate binds the
     *  Java String as raw JSON text both ways, so plain vector text would fail JSON
     *  validation on insert). */
    @Column(columnDefinition = "text")
    private String metadata;

    /** Authoritative link to the SSVC decision-tree leaf this score resolved to, when
     *  scored via com.martecyber.ares.priorization.ssvc. Null for CVSS/Manual scores. */
    @Column(name = "ssvc_leaf_node_id")
    private Long ssvcLeafNodeId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }

    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }

    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Long getSsvcLeafNodeId() { return ssvcLeafNodeId; }
    public void setSsvcLeafNodeId(Long ssvcLeafNodeId) { this.ssvcLeafNodeId = ssvcLeafNodeId; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
