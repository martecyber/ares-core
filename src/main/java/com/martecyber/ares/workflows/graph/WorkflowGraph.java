package com.martecyber.ares.workflows.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Deserialized shape of {@code Workflow.graphDefinition} / {@code WorkflowRun.graphSnapshot} —
 * see the Workflows implementation plan's "Node graph JSON schema" section. Only the fields the
 * backend actually reads are modeled ({@code position} is frontend-only and never touched here);
 * the raw JSON string is always what's persisted, so nothing is lost by not round-tripping it
 * through this model.
 */
public record WorkflowGraph(List<Node> nodes, List<Edge> edges) {

    public WorkflowGraph {
        nodes = nodes == null ? List.of() : nodes;
        edges = edges == null ? List.of() : edges;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String id, String type, NodeData data) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeData(String label, JsonNode config) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String id, String source, String target, String sourceHandle) { }

    /** {@code Edge.sourceHandle} values for non-CONDITION nodes — a plain/unlabeled edge means SUCCESS. */
    public static final String HANDLE_SUCCESS = "success";
    public static final String HANDLE_ERROR = "error";
    /** {@code Edge.sourceHandle} values for CONDITION nodes. */
    public static final String HANDLE_TRUE = "true";
    public static final String HANDLE_FALSE = "false";
    /** {@code Edge.sourceHandle} values for LOOP nodes — {@code HANDLE_LOOP_BODY} is taken once
     *  per iteration (into the body subgraph), {@code HANDLE_LOOP_DONE} once when the loop
     *  finishes (including immediately, for an empty variable). */
    public static final String HANDLE_LOOP_BODY = "loop_body";
    public static final String HANDLE_LOOP_DONE = "loop_done";
}
