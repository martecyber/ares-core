package com.martecyber.ares.webhooks;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * An admin-supplied HMAC signing secret for one ACTION_WEBHOOK_CALL node — see V148's migration
 * comment for why this lives in its own encrypted table rather than the node's own JSONB config,
 * and why it's keyed by {@code (workflowId, nodeId)} rather than a churning trigger/step row id.
 * Unlike {@link WebhookEndpoint}'s secret, this one is never re-displayed once saved — it's
 * authored by the admin to match what the external receiver already expects, not something Ares
 * itself needs to hand back for the admin to paste elsewhere.
 */
@Entity
@Table(name = "outbound_webhook_secret", schema = "ares")
public class OutboundWebhookSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_iv", nullable = false)
    private byte[] secretIv;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] secretCiphertext) { this.secretCiphertext = secretCiphertext; }

    public byte[] getSecretIv() { return secretIv; }
    public void setSecretIv(byte[] secretIv) { this.secretIv = secretIv; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
