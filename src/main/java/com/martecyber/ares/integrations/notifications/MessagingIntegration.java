package com.martecyber.ares.integrations.notifications;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "messaging_integration", schema = "ares")
public class MessagingIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    /** discord | slack | telegram | teams | webhook */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "config_ciphertext", nullable = false)
    private byte[] configCiphertext;

    @Column(name = "config_iv", nullable = false)
    private byte[] configIv;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public byte[] getConfigCiphertext() { return configCiphertext; }
    public void setConfigCiphertext(byte[] v) { this.configCiphertext = v; }
    public byte[] getConfigIv() { return configIv; }
    public void setConfigIv(byte[] v) { this.configIv = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
