package com.martecyber.ares.webhooks;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One inbound webhook — see V147's migration comment for why this is keyed by
 * {@code (workflowId, nodeId)} rather than {@code workflow_trigger.id} (which churns on every
 * workflow save). {@code token} is the public URL slug ({@code POST /api/v1/webhooks/in/{token}});
 * {@code secretCiphertext}/{@code secretIv} hold the HMAC signing secret, AES-256-GCM encrypted
 * via the existing {@code CredentialEncryptionService} — reversible, not hashed, since an admin
 * needs to be able to re-view it when configuring the external caller.
 */
@Entity
@Table(name = "webhook_endpoint", schema = "ares")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_iv", nullable = false)
    private byte[] secretIv;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "request_count", nullable = false)
    private long requestCount = 0;

    @Column(name = "last_triggered_at")
    private OffsetDateTime lastTriggeredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] secretCiphertext) { this.secretCiphertext = secretCiphertext; }

    public byte[] getSecretIv() { return secretIv; }
    public void setSecretIv(byte[] secretIv) { this.secretIv = secretIv; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }

    public OffsetDateTime getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(OffsetDateTime lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
