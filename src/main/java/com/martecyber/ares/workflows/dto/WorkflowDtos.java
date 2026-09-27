package com.martecyber.ares.workflows.dto;

import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowRun;
import com.martecyber.ares.workflows.WorkflowStepRun;
import com.martecyber.ares.workflows.WorkflowTrigger;
import com.martecyber.ares.workflows.WorkflowValidationWarning;
import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class WorkflowDtos {

    private WorkflowDtos() {}

    public record WorkflowDto(
        Long id, String scopeKind, Long scopeId, String name, String description, String status,
        String graphDefinition, Integer version, Long createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        boolean locked, String managedBy,
        List<TriggerDto> triggers,
        /** Non-blocking, save-time advisories (e.g. "no agent in the selected pool currently
         *  reports this tool") — see WorkflowValidationWarning's own doc. Always empty for a
         *  plain read (get/list); only create/update ever populate it, once, for that one
         *  response — it's not persisted or re-computed on every subsequent read. */
        List<WarningDto> warnings
    ) {
        public static WorkflowDto from(Workflow w, List<WorkflowTrigger> triggers) {
            return from(w, triggers, List.of());
        }

        public static WorkflowDto from(Workflow w, List<WorkflowTrigger> triggers, List<WorkflowValidationWarning> warnings) {
            return new WorkflowDto(w.getId(), w.getScopeKind(), w.getScopeId(), w.getName(), w.getDescription(),
                w.getStatus(), w.getGraphDefinition(), w.getVersion(), w.getCreatedBy(), w.getCreatedAt(), w.getUpdatedAt(),
                w.isLocked(), w.getManagedBy(),
                triggers.stream().map(TriggerDto::from).toList(),
                warnings.stream().map(WarningDto::from).toList());
        }
    }

    public record WarningDto(String nodeId, String message) {
        public static WarningDto from(WorkflowValidationWarning w) {
            return new WarningDto(w.nodeId(), w.message());
        }
    }

    public record TriggerDto(Long id, String nodeId, String triggerType, String config, boolean enabled, OffsetDateTime nextRunAt) {
        public static TriggerDto from(WorkflowTrigger t) {
            return new TriggerDto(t.getId(), t.getNodeId(), t.getTriggerType(), t.getConfig(), t.isEnabled(), t.getNextRunAt());
        }
    }

    /** One registered {@code IntegrationActionHandler}'s type, for ACTION_INTEGRATION_CALL's node
     *  editor to cascade Integration type → Instance → Action. */
    public record IntegrationActionCatalogEntry(
        String type, String typeLabel,
        List<IntegrationActionDescriptor> actions,
        List<IntegrationInstanceDescriptor> instances) {}

    public record CreateWorkflowRequest(String name, String description, Map<String, Object> graphDefinition) {}

    public record UpdateWorkflowRequest(String name, String description, String status, Map<String, Object> graphDefinition) {}

    public record RunDto(
        Long id, Long workflowId, String status, String triggerNodeId, String triggeredBy,
        OffsetDateTime startedAt, OffsetDateTime completedAt, String error
    ) {
        public static RunDto from(WorkflowRun r) {
            return new RunDto(r.getId(), r.getWorkflowId(), r.getStatus(), r.getTriggerNodeId(), r.getTriggeredBy(),
                r.getStartedAt(), r.getCompletedAt(), r.getError());
        }
    }

    public record RunDetailDto(
        Long id, Long workflowId, String status, String triggerNodeId, String triggeredBy, String context,
        /** Raw JSON string — the graph as it existed when this run started (see WorkflowRun.graphSnapshot's
         *  own doc comment for why this is a copy, not a live reference). Lets the UI render the run
         *  against the graph it actually executed, even if the workflow definition changed since. */
        String graphSnapshot,
        OffsetDateTime startedAt, OffsetDateTime completedAt, String error, List<StepDto> steps
    ) {
        public static RunDetailDto from(WorkflowRun r, List<WorkflowStepRun> steps) {
            return new RunDetailDto(r.getId(), r.getWorkflowId(), r.getStatus(), r.getTriggerNodeId(), r.getTriggeredBy(),
                r.getContext(), r.getGraphSnapshot(), r.getStartedAt(), r.getCompletedAt(), r.getError(),
                steps.stream().map(StepDto::from).toList());
        }
    }

    public record StepDto(
        Long id, String nodeId, String nodeType, String status, String output, String error,
        String refType, Long refId, OffsetDateTime startedAt, OffsetDateTime completedAt
    ) {
        public static StepDto from(WorkflowStepRun s) {
            return new StepDto(s.getId(), s.getNodeId(), s.getNodeType(), s.getStatus(), s.getOutput(), s.getError(),
                s.getRefType(), s.getRefId(), s.getStartedAt(), s.getCompletedAt());
        }
    }
}
