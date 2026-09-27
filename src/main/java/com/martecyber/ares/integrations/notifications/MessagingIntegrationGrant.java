package com.martecyber.ares.integrations.notifications;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "messaging_integration_grant", schema = "ares")
public class MessagingIntegrationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Null = org-wide grant; non-null = scoped to that project. */
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long v) { this.integrationId = v; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
