package com.martecyber.ares.agents.schedule;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_task_schedule", schema = "ares")
public class AgentTaskSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 120)
    private String name;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(nullable = false, length = 40)
    private String tool;

    @Column(nullable = false, length = 30)
    private String format = "default";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String args = "{}";

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "nac_profile", length = 64)
    private String nacProfile;

    /** Minutes after dispatch/start before tasks created by this schedule are auto-cancelled.
     *  NULL = no timeout. Copied onto each AgentTask created when the schedule fires. */
    @Column(name = "timeout_minutes")
    private Integer timeoutMinutes;

    @Column(name = "cron_expression", nullable = false, length = 120)
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Optional kickoff moment. When set, the first {@code next_run_at} is clamped to
     * {@code max(cronNext, startAt)} so the schedule lies dormant until then. Useful
     * for "start the daily 03:00 scan from next Monday onward".
     */
    @Column(name = "start_at")
    private OffsetDateTime startAt;

    /**
     * Optional batch size. When set and the resolved target list has more entries than
     * this value, {@code enqueueOne()} creates one {@code agent_task} per batch of N
     * targets instead of one large task. Null = no splitting.
     */
    @Column(name = "batch_size")
    private Integer batchSize;

    /**
     * Optional per-fire target cap. When set, only this many targets are sampled
     * (randomly) from the subset not yet covered this rotation cycle.
     */
    @Column(name = "max_targets_per_run")
    private Integer maxTargetsPerRun;

    /**
     * JSONB array of target strings already visited in the current rotation cycle.
     * Null = cycle not started. Reset to empty when all targets have been covered.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "covered_values", columnDefinition = "jsonb")
    private List<String> coveredValues;

    /** Total duration (ms) of the most recently closed run. Maintained by AgentScheduleRunService. */
    @Column(name = "last_run_duration_ms")
    private Long lastRunDurationMs;

    /** Running average over the last 10 closed runs. Recomputed each time a run closes. */
    @Column(name = "avg_run_duration_ms")
    private Long avgRunDurationMs;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }
    public Long getPoolId() { return poolId; }
    public void setPoolId(Long v) { this.poolId = v; }
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

    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(Integer v) { this.timeoutMinutes = v; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String v) { this.cronExpression = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime v) { this.lastRunAt = v; }
    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime v) { this.nextRunAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getStartAt() { return startAt; }
    public void setStartAt(OffsetDateTime v) { this.startAt = v; }
    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer v) { this.batchSize = v; }
    public Integer getMaxTargetsPerRun() { return maxTargetsPerRun; }
    public void setMaxTargetsPerRun(Integer v) { this.maxTargetsPerRun = v; }
    public List<String> getCoveredValues() {
        if (coveredValues == null) return null;
        if (coveredValues instanceof List<?>) return coveredValues;
        // Hibernate may deserialize a JSON object as LinkedHashMap instead of List<String>
        // when generic type info is lost. Return null so the caller starts a fresh cycle.
        return null;
    }
    public void setCoveredValues(List<String> v) { this.coveredValues = v; }
    public Long getLastRunDurationMs() { return lastRunDurationMs; }
    public void setLastRunDurationMs(Long v) { this.lastRunDurationMs = v; }
    public Long getAvgRunDurationMs() { return avgRunDurationMs; }
    public void setAvgRunDurationMs(Long v) { this.avgRunDurationMs = v; }
}
