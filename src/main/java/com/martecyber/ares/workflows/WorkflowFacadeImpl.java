package com.martecyber.ares.workflows;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Thin adapter exposing {@link ManagedWorkflowService} to plugins as the {@code ares-sdk}-owned
 *  {@link WorkflowFacade} — returns the workflow's id instead of the {@link Workflow} entity. */
@Component
class WorkflowFacadeImpl implements WorkflowFacade {

    private final ManagedWorkflowService managedWorkflowService;

    WorkflowFacadeImpl(ManagedWorkflowService managedWorkflowService) {
        this.managedWorkflowService = managedWorkflowService;
    }

    @Override
    public Long createOrReplace(String managedBy, String scopeKind, Long scopeId, String name,
                                String description, Map<String, Object> graphDefinition) {
        return managedWorkflowService.createOrReplace(managedBy, scopeKind, scopeId, name, description, graphDefinition).getId();
    }

    @Override
    public void setEnabled(Long workflowId, boolean enabled) {
        managedWorkflowService.setEnabled(workflowId, enabled);
    }

    @Override
    public Optional<Long> find(String managedBy, String scopeKind, Long scopeId) {
        return managedWorkflowService.find(managedBy, scopeKind, scopeId).map(Workflow::getId);
    }

    @Override
    public void delete(Long workflowId) {
        managedWorkflowService.delete(workflowId);
    }
}
