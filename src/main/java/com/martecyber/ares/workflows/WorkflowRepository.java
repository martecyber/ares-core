package com.martecyber.ares.workflows;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<Workflow, Long>, JpaSpecificationExecutor<Workflow> {

    List<Workflow> findByScopeKindAndScopeId(String scopeKind, Long scopeId);

    /** The upsert lookup {@code ManagedWorkflowService} uses to find "the existing managed
     *  workflow for this schedule" rather than accumulating duplicates on every save. */
    Optional<Workflow> findByManagedByAndScopeKindAndScopeId(String managedBy, String scopeKind, Long scopeId);

    /** For managed-workflow systems that allow several concurrent schedules sharing the same
     *  {@code managedBy} tag (e.g. KB sync's "several cron schedules for the same sync type") —
     *  {@code managedBy} here is a filter tag, not a uniqueness key, unlike the singular finder
     *  above (which {@code createOrUpdateManaged}'s upsert relies on being at-most-one-row). */
    List<Workflow> findByManagedByAndScopeKindAndScopeIdOrderByCreatedAtAsc(String managedBy, String scopeKind, Long scopeId);
}
