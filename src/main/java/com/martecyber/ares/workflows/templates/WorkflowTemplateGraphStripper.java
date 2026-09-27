package com.martecyber.ares.workflows.templates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Strips scope-bound resource ids out of a workflow graph before it's saved as a template — an
 * agent pool, messaging integration, data-source integration, or integration-action instance
 * referenced by id in one org/project's workflow won't exist (or won't mean the same thing) in
 * whatever org/project later instantiates the template. Deliberately an explicit allowlist by
 * node type (matching this codebase's established preference for explicit-over-clever, e.g.
 * {@code WorkflowGraphValidator}'s own per-node-type switch), not a generic deep-scan for
 * anything that looks like an id — most node types need no stripping at all (labels, AQL, cron
 * expressions, templates, entity types, topics are all portable as-is), which is worth keeping
 * an explicit, auditable list rather than inferring.
 * <p>
 * Deliberately operates on the raw JSON tree, not {@link com.martecyber.ares.workflows.graph.
 * WorkflowGraph}'s parsed model — that model doesn't carry {@code position} (frontend-only,
 * dropped on deserialize), so round-tripping a graph through it here would silently lose every
 * node's canvas layout. Working on the tree preserves everything except the specific fields
 * being stripped.
 */
public final class WorkflowTemplateGraphStripper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Node type → config field name to null out. A node type absent here needs no stripping. */
    private static final Map<String, String> STRIPPED_FIELD_BY_NODE_TYPE = Map.of(
        "ACTION_AGENT_TASK", "poolId",
        "ACTION_NOTIFICATION", "integrationId",
        "ACTION_SYNC", "integrationId",
        "ACTION_INTEGRATION_CALL", "integrationId"
    );

    private WorkflowTemplateGraphStripper() {}

    public static String strip(String graphDefinitionJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(graphDefinitionJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid graph_definition JSON", e);
        }
        JsonNode nodes = root.path("nodes");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                String type = node.path("type").asText(null);
                String field = STRIPPED_FIELD_BY_NODE_TYPE.get(type);
                if (field == null) continue;
                JsonNode config = node.path("data").path("config");
                if (config.isObject()) {
                    ((ObjectNode) config).remove(field);
                }
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stripped graph_definition", e);
        }
    }

    /** Node types {@link #strip} ever touches — exposed for tests to assert against everything
     *  else being a deliberate no-op, not an oversight. */
    static Set<String> strippedNodeTypes() {
        return STRIPPED_FIELD_BY_NODE_TYPE.keySet();
    }
}
