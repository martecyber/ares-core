package com.martecyber.ares.workflows;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** One execution of a {@link Workflow}. {@code graphSnapshot} is a copy of the workflow's graph
 *  at trigger time, not a live reference — editing a workflow must never retroactively change
 *  what an in-flight run executes. {@code context} is the namespaced variable bag ({@code
 *  trigger}/{@code steps.<nodeId>.output}) downstream node configs template against, via {@link
 *  com.martecyber.ares.integrations.notifications.MessagingTemplate}. {@code parentStepRunId} is set when this run
 *  was spawned by a CALL_WORKFLOW step — walked for cross-workflow cycle detection. */
@Entity
@Table(name = "workflow_run", schema = "ares")
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "workflow_version", nullable = false)
    private Integer workflowVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_snapshot", nullable = false, columnDefinition = "jsonb")
    private String graphSnapshot;

    @Column(name = "trigger_node_id", nullable = false, length = 64)
    private String triggerNodeId;

    @Column(name = "triggered_by", length = 120)
    private String triggeredBy;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String context = "{}";

    @Column(name = "parent_step_run_id")
    private Long parentStepRunId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(columnDefinition = "text")
    private String error;

    public Long getId() { return id; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public Integer getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(Integer workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getGraphSnapshot() { return graphSnapshot; }
    public void setGraphSnapshot(String graphSnapshot) { this.graphSnapshot = graphSnapshot; }

    public String getTriggerNodeId() { return triggerNodeId; }
    public void setTriggerNodeId(String triggerNodeId) { this.triggerNodeId = triggerNodeId; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public Long getParentStepRunId() { return parentStepRunId; }
    public void setParentStepRunId(Long parentStepRunId) { this.parentStepRunId = parentStepRunId; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
