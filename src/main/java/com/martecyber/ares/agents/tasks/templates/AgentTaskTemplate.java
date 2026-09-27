package com.martecyber.ares.agents.tasks.templates;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Reusable agent-task configuration. Stored in the KB so operators can apply the
 * same tool + args setup across projects. Scheduling (cron, scheduledFor, startAt)
 * and runtime context (poolId, priority) are bound at task-create time, not here.
 */
@Entity
@Table(name = "agent_task_template", schema = "ares")
public class AgentTaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 40)
    private String tool;

    @Column(nullable = false, length = 30)
    private String format = "default";

    /** Snapshot of the tool-specific args (matches the JSONB shape of agent_task.args). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String args = "{}";

    @Column(name = "nac_profile", length = 64)
    private String nacProfile;

    /** Suggested minutes-to-timeout for tasks created from this template. NULL = no default. */
    @Column(name = "timeout_minutes")
    private Integer timeoutMinutes;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getTool() { return tool; }
    public void setTool(String v) { this.tool = v; }

    public String getFormat() { return format; }
    public void setFormat(String v) { this.format = v; }

    public String getArgs() { return args; }
    public void setArgs(String v) { this.args = v; }

    public String getNacProfile() { return nacProfile; }
    public void setNacProfile(String v) { this.nacProfile = v; }

    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(Integer v) { this.timeoutMinutes = v; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long v) { this.creatorId = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
