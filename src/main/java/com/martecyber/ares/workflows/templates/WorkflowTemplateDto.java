package com.martecyber.ares.workflows.templates;

import java.time.OffsetDateTime;

public record WorkflowTemplateDto(
    Long id,
    String name,
    String description,
    /** {@code WorkflowScope} value — which level this template is meant to be instantiated at. */
    String scopeKind,
    /** JSONB string — same shape as workflow.graph_definition (nodes+edges), already stripped
     *  of scope-bound ids. */
    String graphDefinition,
    Long creatorId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static WorkflowTemplateDto from(WorkflowTemplate t) {
        return new WorkflowTemplateDto(
            t.getId(), t.getName(), t.getDescription(), t.getScopeKind(), t.getGraphDefinition(),
            t.getCreatorId(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
