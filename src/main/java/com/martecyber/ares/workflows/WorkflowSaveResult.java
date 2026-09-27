package com.martecyber.ares.workflows;

import java.util.List;

/**
 * {@link WorkflowService#create}/{@link WorkflowService#update}'s return shape — the saved
 * entity plus any non-blocking {@link WorkflowValidationWarning}s the save-time validation
 * pass collected (see {@link WorkflowValidationResult}), so {@link WorkflowController} can
 * surface them to the operator (e.g. "no agent in the selected pool currently reports this
 * tool") without the save itself having been rejected.
 */
public record WorkflowSaveResult(Workflow workflow, List<WorkflowValidationWarning> warnings) {
    public WorkflowSaveResult {
        if (warnings == null) warnings = List.of();
    }
}
