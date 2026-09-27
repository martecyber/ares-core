package com.martecyber.ares.auth;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_mfa_device", schema = "ares")
public class UserMfaDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "secret_ciphertext")
    private byte[] secretCiphertext;

    @Column(length = 50)
    private String label;

    @Column(name = "last_used")
    private OffsetDateTime lastUsed;

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] secretCiphertext) { this.secretCiphertext = secretCiphertext; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public OffsetDateTime getLastUsed() { return lastUsed; }
    public void setLastUsed(OffsetDateTime lastUsed) { this.lastUsed = lastUsed; }
}
