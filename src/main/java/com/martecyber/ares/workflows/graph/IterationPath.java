package com.martecyber.ares.workflows.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered stack of {@code (loopNodeId, iterationIndex)} segments, outer-to-inner — a step's
 * true per-run identity is {@code (nodeId, IterationPath)}, not {@code nodeId} alone, once a LOOP
 * node's body can execute more than once per run. See {@code WorkflowStepRun#getIterationPath}
 * and {@code WorkflowRunService#advance} for the full model. Immutable; every mutator returns a
 * new instance.
 */
public final class IterationPath {

    public record Segment(String loopNodeId, int index) {}

    public static final IterationPath ROOT = new IterationPath(List.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Segment> segments;

    private IterationPath(List<Segment> segments) {
        this.segments = segments;
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** The LOOP node this path's innermost (current) nesting level belongs to. */
    public String currentLoopNodeId() {
        return last().loopNodeId();
    }

    public int currentIndex() {
        return last().index();
    }

    private Segment last() {
        if (segments.isEmpty()) throw new IllegalStateException("root iteration path has no current loop");
        return segments.get(segments.size() - 1);
    }

    /** Opens a new nesting level — a LOOP node's very first gate (iteration 0) for whatever scope
     *  this path currently represents. */
    public IterationPath push(String loopNodeId, int index) {
        List<Segment> next = new ArrayList<>(segments);
        next.add(new Segment(loopNodeId, index));
        return new IterationPath(next);
    }

    /** Increments the innermost segment's index — the feedback edge closing iteration K makes
     *  iteration K+1's gate ready, at the same nesting level. */
    public IterationPath incrementLast() {
        Segment last = last();
        List<Segment> next = new ArrayList<>(segments.subList(0, segments.size() - 1));
        next.add(new Segment(last.loopNodeId(), last.index() + 1));
        return new IterationPath(next);
    }

    /** Decrements the innermost segment's index — the inverse of {@link #incrementLast}, used to
     *  find which iteration's feedback edge would have produced the current gate. */
    public IterationPath decrementLast() {
        Segment last = last();
        List<Segment> next = new ArrayList<>(segments.subList(0, segments.size() - 1));
        next.add(new Segment(last.loopNodeId(), last.index() - 1));
        return new IterationPath(next);
    }

    /** Drops the innermost segment — the {@code loop_done} edge pops back out to the enclosing
     *  (parent) scope once a loop finishes. */
    public IterationPath popLast() {
        last(); // throws if already root
        return new IterationPath(segments.subList(0, segments.size() - 1));
    }

    /** Canonical string form used as the composite map key in {@code advance()}'s {@code
     *  stepsByNode} — e.g. {@code "loopA:0/loopB:1"}, {@code ""} for the root path. */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s.loopNodeId()).append(':').append(s.index());
        }
        return sb.toString();
    }

    public String toJson() {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Segment s : segments) {
            ObjectNode o = arr.addObject();
            o.put("loop", s.loopNodeId());
            o.put("i", s.index());
        }
        return arr.toString();
    }

    public static IterationPath fromJson(String json) {
        if (json == null || json.isBlank()) return ROOT;
        try {
            JsonNode node = MAPPER.readTree(json);
            List<Segment> segs = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    segs.add(new Segment(item.path("loop").asText(), item.path("i").asInt()));
                }
            }
            return new IterationPath(segs);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed iteration path JSON: " + json, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IterationPath other)) return false;
        return segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        return canonical();
    }
}
