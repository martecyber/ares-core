package com.martecyber.ares.workflows;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** A trigger-&gt;condition-&gt;action graph definition. See the Workflows implementation plan
 *  for the full architecture. {@code scopeId} has no FK — it's polymorphic depending on
 *  {@link #scopeKind} (org id / project id / the platform sentinel {@code 0}), same convention
 *  already used by {@code MessagingEventBinding.scopeId}. */
@Entity
@Table(name = "workflow", schema = "ares")
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_kind", nullable = false, length = 20)
    private String scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 20)
    private String status = "draft";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_definition", nullable = false, columnDefinition = "jsonb")
    private String graphDefinition = "{\"nodes\":[],\"edges\":[]}";

    @Column(nullable = false)
    private Integer version = 1;

    /** True for a workflow created/managed by {@code ManagedWorkflowService} on behalf of one of
     *  the legacy per-system schedulers (KB sync, integration schedule, Shodan task, Caido
     *  plugin/API task, Bug Hunting program sync) — see that class's own doc comment. Locked
     *  workflows reject edit/delete/status-toggle through the normal Workflows UI/API ({@link
     *  WorkflowService#update}/{@link WorkflowService#delete}); their lifecycle is instead
     *  controlled from the origin page that created them. */
    @Column(nullable = false)
    private boolean locked = false;

    /** Which origin system owns this workflow when {@link #locked} is true — e.g. {@code
     *  "kb-sync:cve_update"}, {@code "bug-hunting"}. Null for every ordinary, user-authored
     *  workflow. Also doubles as the upsert key ({@code managedBy}+{@code scopeKind}+{@code
     *  scopeId}) {@code ManagedWorkflowService} uses to find "the existing managed workflow for
     *  this schedule" rather than accumulating duplicates on every save. */
    @Column(name = "managed_by", length = 50)
    private String managedBy;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getScopeKind() { return scopeKind; }
    public void setScopeKind(String scopeKind) { this.scopeKind = scopeKind; }

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getGraphDefinition() { return graphDefinition; }
    public void setGraphDefinition(String graphDefinition) { this.graphDefinition = graphDefinition; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String managedBy) { this.managedBy = managedBy; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
