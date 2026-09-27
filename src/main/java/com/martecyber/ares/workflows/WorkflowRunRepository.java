package com.martecyber.ares.workflows;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {

    Page<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(Long workflowId, Pageable pageable);

    boolean existsByWorkflowIdAndStatusIn(Long workflowId, Collection<String> statuses);
}
