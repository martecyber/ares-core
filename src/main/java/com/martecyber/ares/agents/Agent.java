package com.martecyber.ares.agents;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "agent", schema = "ares")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** Single-use code shown to the admin at creation; cleared on first /enroll. */
    @Column(name = "enrollment_code", unique = true, length = 64)
    private String enrollmentCode;

    /** SHA-256 hex of the long-lived bearer token. Null until the agent enrolls. */
    @Column(name = "token_hash", unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 255)
    private String hostname;

    @Column(length = 40)
    private String platform;

    @Column(length = 20)
    private String arch;

    @Column(length = 40)
    private String version;

    /** JSONB array of capability descriptors emitted by the agent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String capabilities = "[]";

    /** JSONB array of {@code {toolId, reason}} — advisory only, see the column's own migration
     *  comment (V201) for why this is never read by task dispatch. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unavailable_tools", nullable = false, columnDefinition = "jsonb")
    private String unavailableTools = "[]";

    /**
     * Number of concurrent tasks this agent is allowed to run. Used by the
     * pool timeline view to render N rows ("slots") per agent. The dispatcher
     * doesn't enforce this yet — it still hands out one task per heartbeat —
     * so for now it's a capacity-planning hint.
     */
    @Column(name = "max_concurrent_tasks", nullable = false)
    private int maxConcurrentTasks = 1;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "registered_by_user")
    private Long registeredByUser;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getEnrollmentCode() { return enrollmentCode; }
    public void setEnrollmentCode(String v) { this.enrollmentCode = v; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getHostname() { return hostname; }
    public void setHostname(String v) { this.hostname = v; }

    public String getPlatform() { return platform; }
    public void setPlatform(String v) { this.platform = v; }

    public String getArch() { return arch; }
    public void setArch(String v) { this.arch = v; }

    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }

    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String v) { this.capabilities = v; }

    public String getUnavailableTools() { return unavailableTools; }
    public void setUnavailableTools(String v) { this.unavailableTools = v; }

    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int v) { this.maxConcurrentTasks = v; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime v) { this.lastSeenAt = v; }

    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(OffsetDateTime v) { this.registeredAt = v; }

    public Long getRegisteredByUser() { return registeredByUser; }
    public void setRegisteredByUser(Long v) { this.registeredByUser = v; }
}
