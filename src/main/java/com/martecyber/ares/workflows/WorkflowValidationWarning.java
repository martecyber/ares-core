package com.martecyber.ares.workflows;

/**
 * A non-blocking advisory attached to a specific node — unlike {@link WorkflowValidationException}
 * (thrown, blocks save), a warning is only ever collected and returned alongside the parsed graph
 * (see {@link WorkflowValidationResult}). Reserved for checks whose truth can change independently
 * of the workflow's own definition — e.g. "no agent in this pool currently reports this tool" is
 * true or false based on live agent state, not anything save-time-definitive about the graph
 * itself, so it must never block a save the way a structural error does.
 */
public record WorkflowValidationWarning(String nodeId, String message) {}
