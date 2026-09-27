package com.martecyber.ares.integrations;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "integration", schema = "ares")
public class Integration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable — Tool Integrations (Tenable, Qualys…) are not tied to a detector_tool. */
    @Column(name = "tool_id")
    private Long toolId;

    @Column(nullable = false, length = 50)
    private String name;

    /** Discriminator: "tenable", "qualys", etc. */
    @Column(nullable = false, length = 50)
    private String type;

    /** PLATFORM = MSSP-owned, shareable via grants. ORGANIZATION = org-specific. */
    @Column(nullable = false, length = 20)
    private String scope = "PLATFORM";

    /** Set when scope=ORGANIZATION. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false, length = 20)
    private String status = "active";

    /** Non-sensitive config (e.g. base URL, platform region). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String settings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String data;

    /** AES-256-GCM encrypted JSON of sensitive credentials. */
    @Column
    private byte[] credentials;

    /** GCM initialization vector paired with credentials. */
    @Column(name = "credential_iv")
    private byte[] credentialIv;

    @Column(name = "connection_status", nullable = false, length = 20)
    private String connectionStatus = "unknown";

    @Column(name = "connection_error", columnDefinition = "TEXT")
    private String connectionError;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public byte[] getCredentials() { return credentials; }
    public void setCredentials(byte[] credentials) { this.credentials = credentials; }

    public byte[] getCredentialIv() { return credentialIv; }
    public void setCredentialIv(byte[] credentialIv) { this.credentialIv = credentialIv; }

    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }

    public String getConnectionError() { return connectionError; }
    public void setConnectionError(String connectionError) { this.connectionError = connectionError; }

    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
