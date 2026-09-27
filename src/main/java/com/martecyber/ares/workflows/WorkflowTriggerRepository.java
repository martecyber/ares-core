package com.martecyber.ares.workflows;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface WorkflowTriggerRepository extends JpaRepository<WorkflowTrigger, Long> {

    List<WorkflowTrigger> findByWorkflowId(Long workflowId);

    void deleteByWorkflowId(Long workflowId);

    @Query("SELECT t FROM WorkflowTrigger t WHERE t.enabled = true AND t.triggerType = 'cron' "
        + "AND t.nextRunAt IS NOT NULL AND t.nextRunAt <= :now")
    List<WorkflowTrigger> findDueCronTriggers(@Param("now") OffsetDateTime now);

    /** Every enabled TRIGGER_EVENT trigger, across all workflows — filtered further in Java
     *  ({@code WorkflowEventDispatcher}) by the node's configured entityType and the owning
     *  workflow's scope/status, since that needs a per-row Workflow lookup anyway and the
     *  expected row count platform-wide is small. */
    List<WorkflowTrigger> findByTriggerTypeAndEnabledTrue(String triggerType);
}
