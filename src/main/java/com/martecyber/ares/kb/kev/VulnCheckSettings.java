package com.martecyber.ares.kb.kev;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** Singleton row (id always 1) holding the encrypted VulnCheck KEV API key. */
@Entity
@Table(name = "kb_vulncheck_settings", schema = "ares")
public class VulnCheckSettings {

    @Id
    private Integer id = 1;

    @Column(name = "api_key")
    private byte[] apiKey;

    @Column(name = "api_key_iv")
    private byte[] apiKeyIv;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public byte[] getApiKey() { return apiKey; }
    public void setApiKey(byte[] apiKey) { this.apiKey = apiKey; }

    public byte[] getApiKeyIv() { return apiKeyIv; }
    public void setApiKeyIv(byte[] apiKeyIv) { this.apiKeyIv = apiKeyIv; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
