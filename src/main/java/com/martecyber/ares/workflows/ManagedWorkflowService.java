package com.martecyber.ares.workflows;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflows Phase H+: the single privileged entry point the 5 legacy per-system schedulers (KB
 * sync, integration schedule, Shodan task, Caido plugin/API task, Bug Hunting program sync) use to
 * create/manage a recurring sync as a real {@link Workflow} instead of their own bespoke table.
 * Every workflow this produces is {@code locked} — {@link WorkflowService#update}/{@link
 * WorkflowService#delete} refuse to touch it, so it's visible (read-only) in the Workflows list at
 * its scope but only actually manageable through the methods here, called from each origin
 * system's own controller/service. Deliberately never exposed over HTTP directly — an origin
 * system's own endpoint is the only caller, so there's no separate access-control story here beyond
 * whatever `@PreAuthorize` that endpoint already has.
 */
@Service
public class ManagedWorkflowService {

    private final WorkflowService workflowService;
    private final WorkflowRepository workflowRepo;

    public ManagedWorkflowService(WorkflowService workflowService, WorkflowRepository workflowRepo) {
        this.workflowService = workflowService;
        this.workflowRepo = workflowRepo;
    }

    /** Creates the managed workflow for this schedule, or updates it in place if one already
     *  exists for the same {@code (managedBy, scopeKind, scopeId)} — callers don't need to check
     *  {@link #find} first, this always does the right thing on both create and edit. */
    public Workflow createOrReplace(String managedBy, String scopeKind, Long scopeId, String name,
                                     String description, Map<String, Object> graphDefinition) {
        return workflowService.createOrUpdateManaged(managedBy, scopeKind, scopeId, name, description, graphDefinition);
    }

    /** For systems allowing several concurrent schedules under the same {@code managedBy} tag —
     *  always inserts a new locked workflow rather than upserting; see {@link
     *  WorkflowService#createManaged}'s own doc comment. */
    public Workflow create(String managedBy, String scopeKind, Long scopeId, String name,
                            String description, Map<String, Object> graphDefinition) {
        return workflowService.createManaged(managedBy, scopeKind, scopeId, name, description, graphDefinition);
    }

    /** Every managed workflow currently tagged with {@code managedBy} at this scope — the listing
     *  counterpart to {@link #create}. */
    public List<Workflow> findAllTagged(String managedBy, String scopeKind, Long scopeId) {
        return workflowRepo.findByManagedByAndScopeKindAndScopeIdOrderByCreatedAtAsc(managedBy, scopeKind, scopeId);
    }

    /** Edits an existing managed workflow's graph in place (e.g. changing just its cron
     *  expression) — the counterpart to {@link #create} for systems that support editing a
     *  schedule rather than only creating/deleting one. */
    public Workflow updateGraph(Long workflowId, Map<String, Object> graphDefinition) {
        return workflowService.updateManagedGraph(workflowId, graphDefinition);
    }

    /** Pause/resume — mirrors each legacy system's own enable/disable toggle. */
    public void setEnabled(Long workflowId, boolean enabled) {
        workflowService.setManagedEnabled(workflowId, enabled);
    }

    public void delete(Long workflowId) {
        workflowService.deleteManaged(workflowId);
    }

    public Optional<Workflow> find(String managedBy, String scopeKind, Long scopeId) {
        return workflowRepo.findByManagedByAndScopeKindAndScopeId(managedBy, scopeKind, scopeId);
    }
}
