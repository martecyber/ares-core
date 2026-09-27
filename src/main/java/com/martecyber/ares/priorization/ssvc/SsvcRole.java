package com.martecyber.ares.priorization.ssvc;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ssvc_role", schema = "ares")
public class SsvcRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "methodology_id", nullable = false)
    private Long methodologyId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** Whether this role's outcomes are eligible to set a finding/template's P0-P4
     *  priority. False for informational-only roles (e.g. Coordinator Triage/Publish),
     *  whose leaf nodes carry no priorityLevel and whose scores can never be default. */
    @Column(name = "uses_priority_mapping", nullable = false)
    private boolean usesPriorityMapping = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** The reusable, named outcome palette this role's tree leaves pick from — stored as
     *  a JSON array of {code,label,description,priorityLevel}, independent of the tree
     *  itself so an outcome survives even before it's assigned to any leaf. Serialized
     *  to/from SsvcOutcomeDto in SsvcMethodologyService. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcomes", nullable = false, columnDefinition = "jsonb")
    private String outcomes = "[]";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getMethodologyId() { return methodologyId; }
    public void setMethodologyId(Long methodologyId) { this.methodologyId = methodologyId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isUsesPriorityMapping() { return usesPriorityMapping; }
    public void setUsesPriorityMapping(boolean usesPriorityMapping) { this.usesPriorityMapping = usesPriorityMapping; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getOutcomes() { return outcomes; }
    public void setOutcomes(String outcomes) { this.outcomes = outcomes; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
