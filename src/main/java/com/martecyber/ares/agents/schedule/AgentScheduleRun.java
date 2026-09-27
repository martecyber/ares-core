package com.martecyber.ares.agents.schedule;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One execution of a recurring agent task schedule. A "run" groups the N tasks
 * created by a single cron fire (a schedule may split its work into batches).
 * The run is closed (completedAt set, totalDurationMs computed) by
 * {@code AgentScheduleRunService.onTaskTerminal} when every task in the run
 * has reached a terminal state.
 */
@Entity
@Table(name = "agent_schedule_run", schema = "ares")
public class AgentScheduleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "task_count", nullable = false)
    private int taskCount;

    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    public Long getId() { return id; }
    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long v) { this.scheduleId = v; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { this.startedAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
    public int getTaskCount() { return taskCount; }
    public void setTaskCount(int v) { this.taskCount = v; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int v) { this.completedCount = v; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int v) { this.failedCount = v; }
    public Long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Long v) { this.totalDurationMs = v; }
}
