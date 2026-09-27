package com.martecyber.ares.integrations.grants;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "integration_grant", schema = "ares")
public class IntegrationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Null = org-wide access. Non-null = restricted to one project. */
    @Column(name = "project_id")
    private Long projectId;

    /** e.g. ["SYNC_ASSETS", "SYNC_VULNS"] stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> capabilities = List.of();

    /** Tenable MSSP: child container UUID used to mint temporary per-account API keys during sync
     *  (see TenableClient.generateChildAccountKeys). */
    @Column(name = "account_id")
    private String accountId;

    /** Tenable MSSP: human-readable name of the managed account shown in the UI. */
    @Column(name = "account_name")
    private String accountName;

    /** Greenbone/OpenVAS: GVM task UUID this grant syncs/launches. One grant = one task —
     *  a project needing results from several tasks gets several grants. */
    @Column(name = "task_id")
    private String taskId;

    /** Greenbone/OpenVAS: human-readable name of the GVM task shown in the UI. */
    @Column(name = "task_name")
    private String taskName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
