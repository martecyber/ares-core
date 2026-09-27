package com.martecyber.ares.workflows;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** One executed node within a {@link WorkflowRun}. {@code refType}/{@code refId} is the
 *  async-wait anchor — which legacy-system row (an {@code AgentTask} or {@code Job}) this step is
 *  waiting on, if any; {@link WorkflowStepPoller} scans {@code status='waiting'} rows to advance
 *  them once the referenced row reaches a terminal state, with zero changes needed to
 *  AgentTaskService/JobService. */
@Entity
@Table(name = "workflow_step_run", schema = "ares")
public class WorkflowStepRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_run_id", nullable = false)
    private Long workflowRunId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 40)
    private String nodeType;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** JSON array of {@code {"loop": "<loopNodeId>", "i": <index>}}, outer-to-inner — which loop
     *  iteration(s) this step instance belongs to. {@code "[]"} for anything never inside a loop,
     *  which is every step except a LOOP node's own repeated gate rows and its body nodes — see
     *  {@code com.martecyber.ares.workflows.graph.LoopBodyResolver} and {@code
     *  WorkflowRunService#advance}. Part of a step's true per-run identity alongside {@code
     *  nodeId}: a body node executes once per iteration, so (nodeId, iterationPath) — not nodeId
     *  alone — is what's unique within a run. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "iteration_path", nullable = false, columnDefinition = "jsonb")
    private String iterationPath = "[]";

    public Long getId() { return id; }

    public Long getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(Long workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public String getIterationPath() { return iterationPath; }
    public void setIterationPath(String iterationPath) { this.iterationPath = iterationPath == null ? "[]" : iterationPath; }
}
