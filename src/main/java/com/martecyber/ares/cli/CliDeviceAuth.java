package com.martecyber.ares.cli;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "cli_device_auth", schema = "ares")
public class CliDeviceAuth {

    public static final String PENDING  = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DENIED   = "DENIED";
    public static final String CONSUMED = "CONSUMED";
    public static final String EXPIRED  = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_code", nullable = false, unique = true, length = 64)
    private String deviceCode;

    @Column(name = "user_code", nullable = false, length = 16)
    private String userCode;

    @Column(name = "client_info", length = 255)
    private String clientInfo;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "user_id")
    private Long userId;

    /** Plaintext of a real user_api_token — set on approve, cleared the moment the CLI polls it. */
    @Column(name = "api_token", length = 128)
    private String apiToken;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public Long getId() { return id; }

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String v) { this.deviceCode = v; }

    public String getUserCode() { return userCode; }
    public void setUserCode(String v) { this.userCode = v; }

    public String getClientInfo() { return clientInfo; }
    public void setClientInfo(String v) { this.clientInfo = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String v) { this.apiToken = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
}
