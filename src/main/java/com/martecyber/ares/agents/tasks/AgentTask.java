package com.martecyber.ares.agents.tasks;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_task", schema = "ares")
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 120)
    private String name;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(nullable = false, length = 40)
    private String tool;

    @Column(nullable = false, length = 30)
    private String format = "default";

    /** Tool-specific arguments as JSONB. Validated by {@code AgentToolSpecRegistry} at create time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String args = "{}";

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "nac_profile", length = 64)
    private String nacProfile;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int priority = 0;

    /** Minutes after dispatch/start before this task is auto-cancelled. NULL = no timeout. */
    @Column(name = "timeout_minutes")
    private Integer timeoutMinutes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /**
     * Future timestamp ⇒ task is queued but not claimable until then. NULL ⇒ run ASAP.
     * The agent-claim query filters by (scheduled_for IS NULL OR scheduled_for <= now()).
     */
    @Column(name = "scheduled_for")
    private OffsetDateTime scheduledFor;

    @Column(name = "schedule_id")
    private Long scheduleId;

    /**
     * One execution of a schedule may create N tasks (batches). All share the
     * same scheduleRunId so {@link com.martecyber.ares.agents.schedule.AgentScheduleRunService}
     * can group them and close the run when every task is terminal.
     * NULL for one-shot tasks created directly by an operator.
     */
    @Column(name = "schedule_run_id")
    private Long scheduleRunId;

    /** Total wall-clock execution time, set when complete()/fail() runs. NULL while still pending/running. */
    @Column(name = "actual_duration_ms")
    private Long actualDurationMs;

    @Column(name = "import_id")
    private Long importId;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(columnDefinition = "text")
    private String stderr;

    @Column(columnDefinition = "text")
    private String error;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }

    public Long getPoolId() { return poolId; }
    public void setPoolId(Long v) { this.poolId = v; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long v) { this.agentId = v; }

    public String getTool() { return tool; }
    public void setTool(String v) { this.tool = v; }

    public String getFormat() { return format; }
    public void setFormat(String v) { this.format = v; }

    public String getArgs() { return args; }
    public void setArgs(String v) { this.args = v; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String v) { this.sourceIp = v; }

    public String getNacProfile() { return nacProfile; }
    public void setNacProfile(String v) { this.nacProfile = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }

    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(Integer v) { this.timeoutMinutes = v; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long v) { this.createdBy = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(OffsetDateTime v) { this.dispatchedAt = v; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }

    public OffsetDateTime getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(OffsetDateTime v) { this.scheduledFor = v; }

    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long v) { this.scheduleId = v; }

    public Long getScheduleRunId() { return scheduleRunId; }
    public void setScheduleRunId(Long v) { this.scheduleRunId = v; }

    public Long getActualDurationMs() { return actualDurationMs; }
    public void setActualDurationMs(Long v) { this.actualDurationMs = v; }


    public Long getImportId() { return importId; }
    public void setImportId(Long v) { this.importId = v; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer v) { this.exitCode = v; }

    public String getStderr() { return stderr; }
    public void setStderr(String v) { this.stderr = v; }

    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
}
