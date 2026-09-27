package com.martecyber.ares.workflows.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared body-subgraph resolution for a LOOP node — used by both {@code WorkflowGraphValidator}
 * (to check the body's shape at save time) and {@code WorkflowRunService} (to know which nodes to
 * treat as "inside this loop" at run time). A LOOP node's body is every node forward-reachable
 * from its {@link WorkflowGraph#HANDLE_LOOP_BODY} edge, with the walk stopping the instant it
 * reaches the loop node itself again — that's the feedback edge closing the loop, not a body
 * member. Nested LOOP nodes fall out of this naturally: walking into a nested loop's own body just
 * adds those nodes to the outer body's set too (a node genuinely is "inside" both loops), and
 * revisiting the same node twice (e.g. an inner loop's own feedback edge landing back on the inner
 * loop node) is a no-op once it's already in the visited set, so no infinite walk.
 */
public final class LoopBodyResolver {

    public record BodyResult(Set<String> bodyNodeIds, List<WorkflowGraph.Edge> feedbackEdges) {}

    private LoopBodyResolver() {}

    public static BodyResult resolve(WorkflowGraph graph, String loopNodeId) {
        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        for (WorkflowGraph.Edge e : graph.edges()) {
            outgoing.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
        }

        WorkflowGraph.Edge bodyEdge = outgoing.getOrDefault(loopNodeId, List.of()).stream()
            .filter(e -> WorkflowGraph.HANDLE_LOOP_BODY.equals(e.sourceHandle()))
            .findFirst().orElse(null);
        if (bodyEdge == null) {
            return new BodyResult(Set.of(), List.of());
        }

        Set<String> bodyNodeIds = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(bodyEdge.target());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (loopNodeId.equals(id) || !bodyNodeIds.add(id)) continue;
            for (WorkflowGraph.Edge e : outgoing.getOrDefault(id, List.of())) {
                queue.add(e.target());
            }
        }

        List<WorkflowGraph.Edge> feedbackEdges = new ArrayList<>();
        for (WorkflowGraph.Edge e : graph.edges()) {
            if (loopNodeId.equals(e.target()) && bodyNodeIds.contains(e.source())) {
                feedbackEdges.add(e);
            }
        }
        return new BodyResult(bodyNodeIds, feedbackEdges);
    }
}
