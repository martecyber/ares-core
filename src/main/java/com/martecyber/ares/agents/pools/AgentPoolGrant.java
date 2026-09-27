package com.martecyber.ares.agents.pools;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_pool_grant", schema = "ares")
public class AgentPoolGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_pool_id", nullable = false)
    private Long agentPoolId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Null = org-wide grant; non-null = scoped to that project. */
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }

    public Long getAgentPoolId() { return agentPoolId; }
    public void setAgentPoolId(Long v) { this.agentPoolId = v; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }

    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
