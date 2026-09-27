package com.martecyber.ares.workflows;

/** A structurally invalid workflow graph (dangling edge, cycle, missing branch handle, an action
 *  type not allowed at this workflow's scope, ...). Mapped to a 400 by GlobalExceptionHandler.
 *  Distinct from a {@code CONDITION} node's own AQL errors, which surface as the underlying
 *  {@code AqlParseException}/{@code AqlFieldNotFoundException} unwrapped — those already carry a
 *  good message and their own 400 mapping. */
public class WorkflowValidationException extends RuntimeException {
    public WorkflowValidationException(String message) {
        super(message);
    }
}
