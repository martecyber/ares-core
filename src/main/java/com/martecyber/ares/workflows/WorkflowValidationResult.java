package com.martecyber.ares.workflows;

import com.martecyber.ares.workflows.graph.WorkflowGraph;

import java.util.List;

/**
 * {@link WorkflowGraphValidator#validate}'s return shape — the parsed graph plus any {@link
 * WorkflowValidationWarning}s collected along the way. Deliberately generic/reusable: any future
 * non-blocking, save-time advisory gets added to the same {@code warnings} list rather than
 * growing a second parallel mechanism.
 */
public record WorkflowValidationResult(WorkflowGraph graph, List<WorkflowValidationWarning> warnings) {
    public WorkflowValidationResult {
        if (warnings == null) warnings = List.of();
    }
}
