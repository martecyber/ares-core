package com.martecyber.ares.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges {@code WorkflowRun.context}'s nested JSON shape ({@code {trigger: {...}, steps:
 * {<nodeId>: {output: {...}}}}}) into the flat dotted-key {@code Map<String,Object>} that {@link
 * com.martecyber.ares.integrations.notifications.MessagingTemplate#render} expects — that engine (reused as-is
 * from messaging bindings, not reinvented) only ever looked up flat variable names before
 * Workflows widened its regex to allow dots, so nothing about it understands real nesting.
 */
public final class WorkflowContextFlattener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowContextFlattener() {}

    public static Map<String, Object> flatten(String contextJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (contextJson == null || contextJson.isBlank()) return out;
        JsonNode root;
        try {
            root = MAPPER.readTree(contextJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt workflow run context JSON", e);
        }
        flattenInto(root, "", out);
        return out;
    }

    private static void flattenInto(JsonNode node, String prefix, Map<String, Object> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(entry.getValue(), key, out);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenInto(node.get(i), prefix + "." + i, out);
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, leafValue(node));
        }
    }

    private static Object leafValue(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        return node.toString();
    }
}
