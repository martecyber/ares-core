package com.martecyber.ares.workflows.templates;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Reusable workflow graph. Stored in the KB so admins can instantiate the same trigger→
 * condition→action shape across orgs/projects. Platform-wide catalog — the row itself lives
 * outside any org/project, same pattern as {@code FindingTemplate}/{@code AgentTaskTemplate} —
 * but unlike those, it still carries {@link #scopeKind}: not a *location* (the row isn't owned by
 * any particular org/project, hence no {@code scopeId}) but a *declaration* of which {@code
 * Workflow.scopeKind} level the graph is meant to be instantiated into, since several node types
 * (e.g. ACTION_AGENT_TASK/ACTION_SYNC) only make sense at one scope level — see {@link
 * com.martecyber.ares.workflows.WorkflowGraphValidator}'s scope-gating, applied to templates the
 * same way it's applied to a real {@code Workflow}. Scope-bound resource ids ({@code poolId},
 * {@code integrationId}) are stripped out of {@link #graphDefinition} before it's ever saved here
 * — see {@link WorkflowTemplateGraphStripper}.
 */
@Entity
@Table(name = "workflow_template", schema = "ares")
public class WorkflowTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** {@code Workflow.scopeKind} vocabulary ({@link com.martecyber.ares.workflows.WorkflowScope})
     *  — which level this template is meant to be instantiated at. */
    @Column(name = "scope_kind", nullable = false, length = 20)
    private String scopeKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_definition", nullable = false, columnDefinition = "jsonb")
    private String graphDefinition;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getScopeKind() { return scopeKind; }
    public void setScopeKind(String v) { this.scopeKind = v; }

    public String getGraphDefinition() { return graphDefinition; }
    public void setGraphDefinition(String v) { this.graphDefinition = v; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long v) { this.creatorId = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
