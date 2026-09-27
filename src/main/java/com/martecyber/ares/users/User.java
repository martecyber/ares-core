package com.martecyber.ares.users;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "\"user\"", schema = "ares")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash")
    private byte[] passwordHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(nullable = false, length = 10)
    private String status = "active";

    @Column(name = "mfa_enforced", nullable = false)
    private boolean mfaEnforced = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "avatar_data")
    private byte[] avatarData;

    @Column(name = "avatar_mime", length = 50)
    private String avatarMime;

    /** Optional opt-in holiday calendar — populates the dashboard with holiday bars. */
    @Column(name = "holiday_calendar_id")
    private Long holidayCalendarId;

    public Long getId() { return id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public byte[] getPasswordHash() { return passwordHash; }
    public void setPasswordHash(byte[] passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isMfaEnforced() { return mfaEnforced; }
    public void setMfaEnforced(boolean mfaEnforced) { this.mfaEnforced = mfaEnforced; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public byte[] getAvatarData() { return avatarData; }
    public void setAvatarData(byte[] avatarData) { this.avatarData = avatarData; }

    public String getAvatarMime() { return avatarMime; }
    public void setAvatarMime(String avatarMime) { this.avatarMime = avatarMime; }

    public Long getHolidayCalendarId() { return holidayCalendarId; }
    public void setHolidayCalendarId(Long holidayCalendarId) { this.holidayCalendarId = holidayCalendarId; }
}
