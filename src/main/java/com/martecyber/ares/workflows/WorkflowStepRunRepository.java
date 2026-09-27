package com.martecyber.ares.workflows;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkflowStepRunRepository extends JpaRepository<WorkflowStepRun, Long> {

    List<WorkflowStepRun> findByWorkflowRunId(Long workflowRunId);

    /** The poller's own scan — matches the partial index {@code ix_workflow_step_run_waiting}. */
    List<WorkflowStepRun> findByStatusAndRefType(String status, String refType);

    /** {@link WorkflowStepPoller}'s stuck-run reconciliation scan — every {@code workflow_run}
     *  still marked {@code running} whose steps have *all* already reached a terminal status
     *  (completed/failed/skipped), so nothing is ever going to call {@code advance()} for it again
     *  on its own (no WAITING step left to poll). Catches a run whose final status update was lost
     *  — e.g. an interrupted/rolled-back {@code advance()} transaction — after every one of its
     *  steps, including a reached END node, was already durably committed independently. */
    @Query("SELECT DISTINCT r.id FROM WorkflowRun r WHERE r.status = 'running' AND NOT EXISTS "
        + "(SELECT 1 FROM WorkflowStepRun s WHERE s.workflowRunId = r.id "
        + "AND s.status IN ('pending', 'running', 'waiting'))")
    List<Long> findRunningWorkflowRunIdsWithNoActiveSteps();
}
