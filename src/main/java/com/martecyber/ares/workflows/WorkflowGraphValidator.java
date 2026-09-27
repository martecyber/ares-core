package com.martecyber.ares.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.Agent;
import com.martecyber.ares.agents.AgentRepository;
import com.martecyber.ares.agents.pools.AgentPoolMember;
import com.martecyber.ares.agents.pools.AgentPoolMemberRepository;
import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlParseException;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.InMemoryResolvableField;
import com.martecyber.ares.aql.registry.PostgresColumnField;
import com.martecyber.ares.workflows.graph.LoopBodyResolver;
import com.martecyber.ares.workflows.graph.WorkflowGraph;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structural + per-node validation for {@code Workflow.graphDefinition}, run on create/update
 * (Workflows implementation plan, Phase A "Node graph JSON schema" section). Structural checks
 * (dangling edges, missing trigger, cycles, branch-handle shape) are save-time and definitive —
 * unlike the CALL_WORKFLOW cross-workflow cycle check, which is necessarily a runtime check since
 * it spans separate workflow definitions (see {@link WorkflowRunService}).
 */
@Component
public class WorkflowGraphValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AqlRegistryLookup aqlRegistries;
    private final AgentToolSpecRegistry agentToolSpecRegistry;
    private final IntegrationActionRegistry integrationActionRegistry;
    private final AgentPoolMemberRepository agentPoolMemberRepo;
    private final AgentRepository agentRepo;

    public WorkflowGraphValidator(AqlRegistryLookup aqlRegistries, AgentToolSpecRegistry agentToolSpecRegistry,
                                   IntegrationActionRegistry integrationActionRegistry,
                                   AgentPoolMemberRepository agentPoolMemberRepo, AgentRepository agentRepo) {
        this.aqlRegistries = aqlRegistries;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
        this.integrationActionRegistry = integrationActionRegistry;
        this.agentPoolMemberRepo = agentPoolMemberRepo;
        this.agentRepo = agentRepo;
    }

    /** Parses and validates a graph, returning the parsed form so callers (the controller,
     *  WorkflowRunService) don't have to parse the JSON a second time. Fully strict — equivalent
     *  to {@code validate(scopeKind, graphJson, true)}. */
    public WorkflowValidationResult validate(String scopeKind, String graphJson) {
        return validate(scopeKind, graphJson, true);
    }

    /** Same as the 4-arg overload but without a {@code scopeId} — every check runs except
     *  whichever per-handler {@code isAvailableForScope} gate needs a concrete scope id (e.g. Bug
     *  Hunting sync is only offered to bug-hunting-typed *projects*, not every project), which is
     *  silently skipped rather than enforced. Kept only because a large existing test suite calls
     *  this shape for concerns unrelated to that gate; every real production caller goes through
     *  {@link WorkflowService}, which always has a real scopeId and uses the 4-arg overload below. */
    public WorkflowValidationResult validate(String scopeKind, String graphJson, boolean requireComplete) {
        return validate(scopeKind, null, graphJson, requireComplete);
    }

    /**
     * @param scopeId the workflow's own scope id (org id / project id / the platform sentinel) —
     *     null is accepted (see the 3-arg overload's doc comment) but skips scope-id-specific
     *     integration-action gating.
     * @param requireComplete when {@code false}, the scope-bound resource-id fields that
     *     {@link com.martecyber.ares.workflows.templates.WorkflowTemplateGraphStripper} strips
     *     (ACTION_AGENT_TASK.poolId, ACTION_INTEGRATION_CALL.integrationId) are allowed
     *     to be absent — every other check (structure, node-type support, scope gating, all other
     *     required fields) still applies unconditionally. A workflow lands in {@code draft} status
     *     whether created from scratch or instantiated from a template, and either way should be
     *     save-able mid-configuration; only activation (status transitioning to {@code active})
     *     needs the resource actually picked. {@link WorkflowService} is the only caller that
     *     decides which mode applies, based on the resulting status.
     */
    public WorkflowValidationResult validate(String scopeKind, Long scopeId, String graphJson, boolean requireComplete) {
        WorkflowGraph graph = parse(graphJson);
        validateStructure(graph);
        validateNodeTypesSupported(graph);
        validateScopeGating(scopeKind, graph);
        List<WorkflowValidationWarning> warnings = new ArrayList<>();
        validateNodeConfigs(scopeKind, scopeId, graph, requireComplete, warnings);
        return new WorkflowValidationResult(graph, warnings);
    }

    private WorkflowGraph parse(String graphJson) {
        try {
            return MAPPER.readValue(graphJson, WorkflowGraph.class);
        } catch (JsonProcessingException e) {
            throw new WorkflowValidationException("Malformed workflow graph JSON: " + e.getOriginalMessage());
        }
    }

    private void validateStructure(WorkflowGraph graph) {
        Map<String, WorkflowGraph.Node> byId = new HashMap<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (byId.put(node.id(), node) != null) {
                throw new WorkflowValidationException("Duplicate node id '" + node.id() + "'");
            }
        }
        if (graph.nodes().stream().noneMatch(n -> WorkflowNodeType.isTrigger(n.type()))) {
            throw new WorkflowValidationException("A workflow needs at least one trigger node");
        }

        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        for (WorkflowGraph.Edge edge : graph.edges()) {
            if (!byId.containsKey(edge.source())) {
                throw new WorkflowValidationException("Edge '" + edge.id() + "' references unknown source node '" + edge.source() + "'");
            }
            if (!byId.containsKey(edge.target())) {
                throw new WorkflowValidationException("Edge '" + edge.id() + "' references unknown target node '" + edge.target() + "'");
            }
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
        }

        for (WorkflowGraph.Node node : graph.nodes()) {
            List<WorkflowGraph.Edge> out = outgoing.getOrDefault(node.id(), List.of());
            if (WorkflowNodeType.CONDITION.equals(node.type())) {
                boolean hasTrue = out.stream().anyMatch(e -> WorkflowGraph.HANDLE_TRUE.equals(e.sourceHandle()));
                boolean hasFalse = out.stream().anyMatch(e -> WorkflowGraph.HANDLE_FALSE.equals(e.sourceHandle()));
                if (!hasTrue || !hasFalse) {
                    throw new WorkflowValidationException(
                        "CONDITION node '" + node.id() + "' must have both a 'true' and a 'false' outgoing edge");
                }
                for (WorkflowGraph.Edge e : out) {
                    if (!WorkflowGraph.HANDLE_TRUE.equals(e.sourceHandle()) && !WorkflowGraph.HANDLE_FALSE.equals(e.sourceHandle())) {
                        throw new WorkflowValidationException(
                            "CONDITION node '" + node.id() + "' outgoing edge '" + e.id() + "' must be handle 'true' or 'false'");
                    }
                }
            } else if (WorkflowNodeType.END.equals(node.type())) {
                if (!out.isEmpty()) {
                    throw new WorkflowValidationException(
                        "END node '" + node.id() + "' can't have outgoing edges — it's a terminal node");
                }
            } else if (WorkflowNodeType.LOOP.equals(node.type())) {
                boolean hasBody = out.stream().anyMatch(e -> WorkflowGraph.HANDLE_LOOP_BODY.equals(e.sourceHandle()));
                boolean hasDone = out.stream().anyMatch(e -> WorkflowGraph.HANDLE_LOOP_DONE.equals(e.sourceHandle()));
                if (!hasBody || !hasDone) {
                    throw new WorkflowValidationException(
                        "LOOP node '" + node.id() + "' must have both a 'loop_body' and a 'loop_done' outgoing edge");
                }
                for (WorkflowGraph.Edge e : out) {
                    if (!WorkflowGraph.HANDLE_LOOP_BODY.equals(e.sourceHandle()) && !WorkflowGraph.HANDLE_LOOP_DONE.equals(e.sourceHandle())) {
                        throw new WorkflowValidationException(
                            "LOOP node '" + node.id() + "' outgoing edge '" + e.id() + "' must be handle 'loop_body' or 'loop_done'");
                    }
                }
                // LoopBodyResolver's own walk already IS the definition of "inside the body" (every
                // node forward-reachable from the loop_body edge, however it gets there) — so a
                // body node's edge can never actually "leak" anywhere outside that set; wherever it
                // points either becomes part of the body by that same walk, or is the LOOP node
                // itself (the feedback edge). The only thing left to require is that the walk
                // actually reaches back to the LOOP node at least once — otherwise nothing would
                // ever make its next gate ready.
                if (LoopBodyResolver.resolve(graph, node.id()).feedbackEdges().isEmpty()) {
                    throw new WorkflowValidationException("LOOP node '" + node.id()
                        + "': its body never feeds back into the loop — wire an edge from the end of the body back to this LOOP node");
                }
            } else {
                for (WorkflowGraph.Edge e : out) {
                    String h = e.sourceHandle();
                    if (h != null && !WorkflowGraph.HANDLE_SUCCESS.equals(h) && !WorkflowGraph.HANDLE_ERROR.equals(h)) {
                        throw new WorkflowValidationException(
                            "Node '" + node.id() + "' outgoing edge '" + e.id() + "' must be handle 'success' or 'error', got '" + h + "'");
                    }
                }
            }
        }

        assertAcyclic(graph, outgoing);
    }

    /** Standard DFS 3-color cycle detection over the whole node/edge set — a workflow graph must
     *  be a true DAG (unlike the CALL_WORKFLOW cross-workflow case, this can be checked in full
     *  since it's all one graph) — with exactly one sanctioned exception: a LOOP node's own
     *  feedback edge(s) (see {@link LoopBodyResolver}), which are the deliberate cycle a LOOP node
     *  needs. Every other cycle, including one hiding inside a LOOP's own body, is still rejected
     *  exactly as before. */
    private void assertAcyclic(WorkflowGraph graph, Map<String, List<WorkflowGraph.Edge>> outgoing) {
        Set<WorkflowGraph.Edge> sanctionedFeedback = new HashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (WorkflowNodeType.LOOP.equals(node.type())) {
                sanctionedFeedback.addAll(LoopBodyResolver.resolve(graph, node.id()).feedbackEdges());
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (!done.contains(node.id())) {
                dfs(node.id(), outgoing, visiting, done, sanctionedFeedback);
            }
        }
    }

    private void dfs(String nodeId, Map<String, List<WorkflowGraph.Edge>> outgoing, Set<String> visiting,
                      Set<String> done, Set<WorkflowGraph.Edge> sanctionedFeedback) {
        visiting.add(nodeId);
        for (WorkflowGraph.Edge edge : outgoing.getOrDefault(nodeId, List.of())) {
            if (sanctionedFeedback.contains(edge)) continue;
            if (visiting.contains(edge.target())) {
                throw new WorkflowValidationException("Workflow graph contains a cycle at node '" + edge.target() + "'");
            }
            if (!done.contains(edge.target())) {
                dfs(edge.target(), outgoing, visiting, done, sanctionedFeedback);
            }
        }
        visiting.remove(nodeId);
        done.add(nodeId);
    }

    private void validateNodeTypesSupported(WorkflowGraph graph) {
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (!WorkflowNodeType.SUPPORTED_NODE_TYPES.contains(node.type())) {
                throw new WorkflowValidationException(
                    "Node type '" + node.type() + "' (node '" + node.id() + "') is not supported yet");
            }
        }
    }

    private void validateScopeGating(String scopeKind, WorkflowGraph graph) {
        if (WorkflowScope.PROJECT.equals(scopeKind)) return;
        boolean hasAgentTask = graph.nodes().stream().anyMatch(n -> WorkflowNodeType.ACTION_AGENT_TASK.equals(n.type()));
        if (hasAgentTask) {
            throw new WorkflowValidationException(
                "ACTION_AGENT_TASK nodes are only allowed in project-scoped workflows — agent tasks always belong to a project");
        }
        // ACTION_INTEGRATION_CALL is no longer blanket-gated to project scope here — each
        // registered IntegrationActionHandler declares its own supportedScopes() (Caido is
        // project-only, KB sync is platform-only, Shodan is org-only), checked per-node in
        // validateIntegrationCall below since it depends on which integrationType was picked.
    }

    private void validateNodeConfigs(String scopeKind, Long scopeId, WorkflowGraph graph, boolean requireComplete,
                                      List<WorkflowValidationWarning> warnings) {
        Map<String, Integer> incomingCountByTarget = new HashMap<>();
        for (WorkflowGraph.Edge e : graph.edges()) {
            incomingCountByTarget.merge(e.target(), 1, Integer::sum);
        }
        for (WorkflowGraph.Node node : graph.nodes()) {
            JsonNode config = node.data() == null ? null : node.data().config();
            validateJoinThreshold(node.id(), node.type(), config, incomingCountByTarget.getOrDefault(node.id(), 0));
            switch (node.type()) {
                case WorkflowNodeType.CONDITION -> validateCondition(scopeKind, graph, node.id(), config);
                case WorkflowNodeType.ASSIGN_VARIABLE -> validateAssignVariable(scopeKind, node.id(), config);
                case WorkflowNodeType.ACTION_AGENT_TASK -> validateAgentTask(graph, node.id(), config, requireComplete, warnings);
                case WorkflowNodeType.ACTION_INTEGRATION_CALL -> validateIntegrationCall(scopeKind, scopeId, node.id(), config, requireComplete);
                case WorkflowNodeType.TRIGGER_CRON -> validateCron(node.id(), config);
                case WorkflowNodeType.TRIGGER_EVENT -> validateTriggerEvent(scopeKind, node.id(), config);
                case WorkflowNodeType.ACTION_CALL_WORKFLOW -> validateCallWorkflow(node.id(), config);
                case WorkflowNodeType.ACTION_WEBHOOK_CALL -> validateWebhookCall(node.id(), config);
                case WorkflowNodeType.TRIGGER_CALL_TOPIC -> validateCallTopic(node.id(), config);
                case WorkflowNodeType.ACTION_NOTIFICATION -> validateNotification(node.id(), config);
                case WorkflowNodeType.ACTION_MANAGE_TAGS -> validateManageTags(graph, node.id(), config);
                case WorkflowNodeType.ACTION_UPDATE_DETECTION_STATUS -> validateUpdateDetectionStatus(graph, node.id(), config);
                case WorkflowNodeType.ACTION_REPORT_FINDING -> validateReportFinding(node.id(), config);
                case WorkflowNodeType.LOOP -> validateLoop(graph, node.id(), config);
                case WorkflowNodeType.END -> validateEnd(node.id(), config);
                default -> { /* TRIGGER_MANUAL, TRIGGER_WEBHOOK: structural presence only for now */ }
            }
        }
    }

    /**
     * Validates the generic per-node join-threshold config every node type can carry (see
     * {@code WorkflowRunService#resolveJoinThreshold} for how it's actually applied at run time —
     * kept structural-only here, same "shape now, behavior there" split as everywhere else in
     * this validator). {@code joinMode} absent or {@code "ALL"} needs no further checks. {@code
     * "AT_LEAST"} requires a positive {@code joinCount}, and — since the graph is fully known at
     * save time — rejects a count greater than the node's own actual incoming-edge count outright
     * rather than silently clamping it at run time, so a typo doesn't quietly turn into "ALL"
     * without the author noticing. LOOP nodes are exempt: the engine always ignores this config
     * for them (a LOOP gate's own entry/feedback-edge semantics don't compose with a generic
     * threshold — see resolveJoinThreshold's own doc), so validating it here would only produce
     * confusing errors on a field the node never actually acts on.
     */
    private void validateJoinThreshold(String nodeId, String nodeType, JsonNode config, int incomingCount) {
        if (config == null || WorkflowNodeType.LOOP.equals(nodeType)) return;
        String mode = text(config, "joinMode");
        if (mode == null || "ALL".equals(mode)) return;
        if (!"AT_LEAST".equals(mode)) {
            throw new WorkflowValidationException("Node '" + nodeId + "': unknown 'joinMode' '" + mode + "'");
        }
        JsonNode countNode = config.get("joinCount");
        if (countNode == null || !countNode.isIntegralNumber() || countNode.asInt() < 1) {
            throw new WorkflowValidationException("Node '" + nodeId + "' (joinMode AT_LEAST) requires a positive integer 'joinCount'");
        }
        int count = countNode.asInt();
        if (count > incomingCount) {
            throw new WorkflowValidationException("Node '" + nodeId + "': joinCount (" + count
                + ") can't exceed its own incoming edge count (" + incomingCount + ")");
        }
    }

    /** Asset/Detection/Finding always belong to a specific project — a platform-scoped workflow
     *  has no project context to resolve them against, so conditions/queries on them are rejected
     *  there the same way ACTION_AGENT_TASK is (see validateScopeGating above). Organization and
     *  project scope both allow all three; nothing else is offered as a CONDITION/ASSIGN_VARIABLE
     *  entity today. Shared between validateCondition and validateAssignVariable. */
    private static final Set<String> PROJECT_SCOPED_ENTITIES = Set.of("detection", "finding", "asset");

    /** {@code mode} dispatch: {@code ENTITY_MATCH} (default, backward-compatible with every
     *  already-saved CONDITION node — see {@link #validateConditionEntityMatch}) checks one
     *  already-bound entity against an AQL boolean expression; {@code COUNT_COMPARE} (see {@link
     *  #validateCountOperand}) compares two counts — of an AQL query's matches, of an already-
     *  assigned ASSIGN_VARIABLE variable's items, or a literal number — with a numeric operator. */
    private void validateCondition(String scopeKind, WorkflowGraph graph, String nodeId, JsonNode config) {
        String mode = text(config, "mode");
        if (mode == null || "ENTITY_MATCH".equals(mode)) {
            validateConditionEntityMatch(scopeKind, nodeId, config);
            return;
        }
        if (!"COUNT_COMPARE".equals(mode)) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "': unknown 'mode' '" + mode + "'");
        }
        String operator = text(config, "operator");
        if (operator == null || !COUNT_COMPARE_OPERATORS.contains(operator)) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId
                + "' (COUNT_COMPARE) requires 'operator' to be one of " + COUNT_COMPARE_OPERATORS);
        }
        validateCountOperand(scopeKind, graph, nodeId, "left", config.get("left"));
        validateCountOperand(scopeKind, graph, nodeId, "right", config.get("right"));
    }

    private void validateConditionEntityMatch(String scopeKind, String nodeId, JsonNode config) {
        String entityType = text(config, "entityType");
        String aql = text(config, "aql");
        if (entityType == null || aql == null) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "' requires 'entityType' and 'aql'");
        }
        if (WorkflowScope.PLATFORM.equals(scopeKind) && PROJECT_SCOPED_ENTITIES.contains(entityType)) {
            throw new WorkflowValidationException(
                "CONDITION node '" + nodeId + "': entity '" + entityType + "' always belongs to a project — "
                    + "platform-scoped workflows can't condition on it. Use an organization or project workflow instead.");
        }
        EntityAqlRegistry<?> registry;
        try {
            registry = aqlRegistries.require(entityType);
        } catch (IllegalArgumentException e) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "': " + e.getMessage());
        }
        // {{...}} is resolved against the run context before parsing (MessagingTemplate.render, see
        // WorkflowRunService#executeConditionEntityMatch) — deferred to run time, same as COUNT_COMPARE's
        // QUERY operand and ASSIGN_VARIABLE's per-source aql below.
        if (containsTemplatePlaceholder(aql)) return;
        AqlNode ast;
        try {
            ast = AqlParser.parse(aql);
        } catch (AqlParseException e) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "': " + e.getMessage());
        }
        assertFieldsInMemoryResolvable(nodeId, ast, registry);
    }

    private static final Set<String> COUNT_COMPARE_OPERATORS = Set.of("EQ", "NEQ", "GT", "GTE", "LT", "LTE");

    /** {@code left}/{@code right} operand of a COUNT_COMPARE CONDITION: {@code kind} is
     *  {@code QUERY} (entityType+aql, same pair ENTITY_MATCH uses, counted not fetched — see
     *  {@link WorkflowRunService}'s {@code countByAql} dispatch), {@code VARIABLE} (references an
     *  ASSIGN_VARIABLE node elsewhere in this graph by name — same cross-node lookup style {@link
     *  #validateManageTags} already uses), or {@code LITERAL} (a plain number). */
    private void validateCountOperand(String scopeKind, WorkflowGraph graph, String nodeId, String side, JsonNode operand) {
        if (operand == null || operand.isNull()) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE) requires '" + side + "'");
        }
        String kind = text(operand, "kind");
        switch (kind == null ? "" : kind) {
            case "QUERY" -> {
                String entityType = text(operand, "entityType");
                String aql = text(operand, "aql");
                if (entityType == null || aql == null) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE."
                        + side + "): 'QUERY' requires 'entityType' and 'aql'");
                }
                if (WorkflowScope.PLATFORM.equals(scopeKind) && PROJECT_SCOPED_ENTITIES.contains(entityType)) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE." + side
                        + "): entity '" + entityType + "' always belongs to a project — platform-scoped workflows can't query it.");
                }
                try {
                    aqlRegistries.require(entityType);
                } catch (IllegalArgumentException e) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE." + side + "): " + e.getMessage());
                }
                // {{...}} is resolved against the run context before parsing (MessagingTemplate.render,
                // see WorkflowRunService#countByAql) — the raw, un-rendered text isn't valid AQL syntax
                // by itself, so parse-checking it here would always fail. Real validation happens at
                // run time instead, same deferral validateTargetsFrom already uses for asset_aql.
                if (containsTemplatePlaceholder(aql)) break;
                try {
                    AqlParser.parse(aql);
                } catch (AqlParseException e) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE." + side + "): " + e.getMessage());
                }
            }
            case "VARIABLE" -> {
                String variableName = text(operand, "variableName");
                if (variableName == null) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE."
                        + side + "): 'VARIABLE' requires 'variableName'");
                }
                boolean exists = graph.nodes().stream().anyMatch(n -> WorkflowNodeType.ASSIGN_VARIABLE.equals(n.type())
                    && variableName.equals(text(n.data() == null ? null : n.data().config(), "variableName")));
                if (!exists) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE." + side
                        + "): no ASSIGN_VARIABLE node in this workflow assigns a variable named '" + variableName + "'");
                }
            }
            case "LITERAL" -> {
                JsonNode value = operand.get("value");
                if (value == null || !value.isNumber()) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE."
                        + side + "): 'LITERAL' requires a numeric 'value'");
                }
            }
            default -> throw new WorkflowValidationException("CONDITION node '" + nodeId + "' (COUNT_COMPARE."
                + side + "): unknown 'kind' '" + kind + "'");
        }
    }

    private <T> void assertFieldsInMemoryResolvable(String nodeId, AqlNode node, EntityAqlRegistry<T> registry) {
        switch (node) {
            case AqlNode.And and -> and.operands().forEach(n -> assertFieldsInMemoryResolvable(nodeId, n, registry));
            case AqlNode.Or or -> or.operands().forEach(n -> assertFieldsInMemoryResolvable(nodeId, n, registry));
            case AqlNode.Not not -> assertFieldsInMemoryResolvable(nodeId, not.operand(), registry);
            case AqlNode.Comparison cmp -> {
                AqlField<T> field = registry.requireField(cmp.field());
                if (!(field instanceof InMemoryResolvableField)) {
                    throw new WorkflowValidationException("CONDITION node '" + nodeId + "': field '" + cmp.field()
                        + "' cannot be evaluated in a Workflow condition yet");
                }
            }
            case AqlNode.BareTerm ignored -> { }
        }
    }

    private static final java.util.regex.Pattern VARIABLE_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** Matches {@code NotificationMessage.colorRgb()}'s recognized severity strings — kept in sync
     *  by hand since the two live in different modules (messaging vs workflows). An unrecognized
     *  value doesn't break delivery (colorRgb() falls back to a neutral default), but rejecting it
     *  at save time surfaces the typo immediately instead of silently losing the color accent. */
    private static final java.util.Set<String> NOTIFICATION_SEVERITIES =
        java.util.Set.of("critical", "high", "medium", "low", "info");

    private static final java.util.Set<String> END_RESULTS = java.util.Set.of("success", "failure");

    /** END — see {@link WorkflowNodeType#END}'s own doc comment. {@code message} is optional,
     *  templated the same way ACTION_NOTIFICATION's bodyTemplate is (see {@code
     *  WorkflowRunService#executeEnd}), so no parse-time check on it beyond being a string. */
    private void validateEnd(String nodeId, JsonNode config) {
        String result = text(config, "result");
        if (result == null || !END_RESULTS.contains(result)) {
            throw new WorkflowValidationException("END node '" + nodeId + "' requires 'result' to be one of " + END_RESULTS);
        }
    }

    private void validateNotification(String nodeId, JsonNode config) {
        String severity = text(config, "severity");
        if (severity != null && !NOTIFICATION_SEVERITIES.contains(severity.toLowerCase())) {
            throw new WorkflowValidationException(
                "ACTION_NOTIFICATION node '" + nodeId + "': 'severity' must be one of " + NOTIFICATION_SEVERITIES + " (or omitted)");
        }
    }

    private static final Set<String> REPORT_FINDING_MODES = Set.of("document", "email");

    /** Structural only — {@code findingId} is a templated string (resolved at run time, may be
     *  {@code {{trigger.entityId}}}) so it can't be validated as a real id here, same reason
     *  {@code integrationId} isn't resolved against the DB in {@link #validateNotification}. */
    private void validateReportFinding(String nodeId, JsonNode config) {
        String findingId = text(config, "findingId");
        if (findingId == null || findingId.isBlank()) {
            throw new WorkflowValidationException("ACTION_REPORT_FINDING node '" + nodeId + "' requires 'findingId'");
        }
        String mode = text(config, "mode");
        if (mode == null || !REPORT_FINDING_MODES.contains(mode)) {
            throw new WorkflowValidationException(
                "ACTION_REPORT_FINDING node '" + nodeId + "': 'mode' must be one of " + REPORT_FINDING_MODES);
        }
        if ("document".equals(mode)) {
            JsonNode templateId = config.get("reportTemplateId");
            if (templateId == null || !templateId.isNumber()) {
                throw new WorkflowValidationException(
                    "ACTION_REPORT_FINDING node '" + nodeId + "' requires 'reportTemplateId' when mode is 'document'");
            }
        } else {
            JsonNode emailTemplateId = config.get("emailTemplateId");
            JsonNode integrationId = config.get("integrationId");
            if (emailTemplateId == null || !emailTemplateId.isNumber() || integrationId == null || !integrationId.isNumber()) {
                throw new WorkflowValidationException(
                    "ACTION_REPORT_FINDING node '" + nodeId + "' requires 'emailTemplateId' and 'integrationId' when mode is 'email'");
            }
        }
    }

    private static final Set<String> SCALAR_VARIABLE_TYPES = Set.of("string", "number", "date", "boolean");

    /** ASSIGN_VARIABLE (Operations group, alongside CONDITION) — a variable is always a list
     *  (possibly empty), built from one or more inline AQL {@code sources}. A {@code variableType}
     *  naming a registered entity requires every source to query that exact entity, whole-row
     *  (no projection); a scalar {@code variableType} (string/number/date/boolean) lets every
     *  source query ANY registered entity, as long as it projects a {@code field} whose resolved
     *  type matches. &ge;2 sources require a {@code combineMode} (union/intersection) to reduce
     *  them to one list — see {@link WorkflowRunService#executeAssignVariable}. */
    private void validateAssignVariable(String scopeKind, String nodeId, JsonNode config) {
        String variableName = text(config, "variableName");
        if (variableName == null || !VARIABLE_NAME.matcher(variableName).matches()) {
            throw new WorkflowValidationException(
                "ASSIGN_VARIABLE node '" + nodeId + "' requires a 'variableName' starting with a letter, containing only letters/digits/underscore");
        }
        String variableType = text(config, "variableType");
        if (variableType == null) {
            throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "' requires a 'variableType'");
        }
        boolean isScalar = SCALAR_VARIABLE_TYPES.contains(variableType);
        if (!isScalar) {
            try {
                aqlRegistries.require(variableType);
            } catch (IllegalArgumentException e) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': unknown 'variableType' '" + variableType + "'");
            }
        }

        JsonNode sourcesNode = config.get("sources");
        if (sourcesNode == null || !sourcesNode.isArray() || sourcesNode.isEmpty()) {
            throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "' requires at least one entry in 'sources'");
        }
        if (sourcesNode.size() > 1) {
            String combineMode = text(config, "combineMode");
            if (!"union".equals(combineMode) && !"intersection".equals(combineMode)) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId
                    + "': 'combineMode' must be 'union' or 'intersection' when there's more than one source");
            }
        }

        for (JsonNode source : sourcesNode) {
            String entityType = text(source, "entityType");
            String aql = text(source, "aql");
            if (entityType == null || aql == null) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': each source requires 'entityType' and 'aql'");
            }
            EntityAqlRegistry<?> registry;
            try {
                registry = aqlRegistries.require(entityType);
            } catch (IllegalArgumentException e) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': " + e.getMessage());
            }
            if (PROJECT_SCOPED_ENTITIES.contains(entityType) && WorkflowScope.PLATFORM.equals(scopeKind)) {
                throw new WorkflowValidationException(
                    "ASSIGN_VARIABLE node '" + nodeId + "': entity '" + entityType + "' always belongs to a project — "
                        + "platform-scoped workflows can't query it. Use an organization or project workflow instead.");
            }
            // {{...}} is resolved against the run context before parsing (MessagingTemplate.render,
            // see WorkflowRunService#executeAssignVariable) — deferred to run time, same as
            // CONDITION's aql fields above.
            if (!containsTemplatePlaceholder(aql)) {
                try {
                    AqlParser.parse(aql);
                } catch (AqlParseException e) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': " + e.getMessage());
                }
            }

            String field = text(source, "field");
            if (isScalar) {
                if (field == null) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId
                        + "': a scalar 'variableType' requires every source to project a 'field'");
                }
                if (PROJECT_SCOPED_ENTITIES.contains(entityType)) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': entity '" + entityType
                        + "' doesn't support field projection yet — only whole-entity sources are supported for asset/finding/detection");
                }
                AqlField<?> aqlField;
                try {
                    aqlField = registry.requireField(field);
                } catch (RuntimeException e) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': " + e.getMessage());
                }
                if (!(aqlField instanceof PostgresColumnField)) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': field '" + field
                        + "' on entity '" + entityType + "' can't be projected into a variable — only plain or array columns support this");
                }
                String resolvedType = scalarTypeOf(aqlField);
                if (!resolvedType.equals(variableType)) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': field '" + field
                        + "' resolves to type '" + resolvedType + "', not '" + variableType + "'");
                }
            } else {
                if (field != null) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId
                        + "': entity variableType '" + variableType + "' doesn't take a 'field' projection");
                }
                if (!entityType.equals(variableType)) {
                    throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': source entity '" + entityType
                        + "' doesn't match variableType '" + variableType + "'");
                }
            }
        }

        JsonNode limitNode = config.get("limit");
        if (limitNode != null && !limitNode.isNull()) {
            validateAssignVariableLimit(nodeId, variableType, isScalar, limitNode);
        }
    }

    /** Fixed sort-key vocabulary each scoped service's own {@code listByAql} already accepts
     *  (AssetService.buildAssetSort/FindingService.buildFindingSort/DetectionService.buildSort) —
     *  reused here rather than a generic AQL-field picker because {@link
     *  com.martecyber.ares.aql.registry.InMemoryResolvableField} (the only in-memory field-value
     *  mechanism this codebase has) covers almost none of Asset/Finding's fields and only 7 of
     *  Detection's, so an arbitrary-field post-combine sort isn't reliably resolvable against the
     *  DTOs {@code listByAql} actually returns — see {@link WorkflowRunService#executeAssignVariable}. */
    private static final Set<String> ASSET_LIMIT_SORT_KEYS = Set.of("identifier", "type", "code", "createdat");
    private static final Set<String> FINDING_LIMIT_SORT_KEYS = Set.of("title", "priority", "duedate", "reportedat", "updatedat", "createdat");
    private static final Set<String> DETECTION_LIMIT_SORT_KEYS = Set.of("severity", "title", "lastseen", "status", "source", "createdat");
    private static final Set<String> LIMIT_STRATEGIES = Set.of("ORDER", "RANDOM");
    private static final Set<String> LIMIT_DIRECTIONS = Set.of("ASC", "DESC");

    /** Optional cap on how many items ASSIGN_VARIABLE's final combined/deduped list keeps — applied
     *  once, after union/intersection (see {@link WorkflowRunService#executeAssignVariable}), never
     *  per-source. {@code strategy: RANDOM} needs no field; {@code strategy: ORDER} needs a {@code
     *  field} + {@code direction} — for {@code asset}/{@code finding}/{@code detection}
     *  variableTypes, {@code field} is restricted to that service's own fixed sort-key vocabulary
     *  (see the *_LIMIT_SORT_KEYS constants above); for a scalar variableType there's no sub-field
     *  to pick (the item list *is* the values, ordered by themselves); for every other (KB/catalog)
     *  variableType, {@code field} is any {@link com.martecyber.ares.aql.registry.PostgresColumnField}-kind
     *  field on that entity's registry — the same "projectable" restriction {@code
     *  AqlQueryableEntityRegistry#queryProjected} already enforces. */
    private void validateAssignVariableLimit(String nodeId, String variableType, boolean isScalar, JsonNode limitNode) {
        JsonNode countNode = limitNode.get("count");
        if (countNode == null || !countNode.isIntegralNumber() || countNode.asInt() <= 0) {
            throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': 'limit.count' must be a positive integer");
        }
        String strategy = text(limitNode, "strategy");
        if (strategy == null || !LIMIT_STRATEGIES.contains(strategy)) {
            throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': 'limit.strategy' must be one of " + LIMIT_STRATEGIES);
        }
        if ("RANDOM".equals(strategy)) {
            if (limitNode.hasNonNull("field")) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': 'limit.field' isn't used when 'strategy' is 'RANDOM'");
            }
            return;
        }

        String direction = text(limitNode, "direction");
        if (direction == null || !LIMIT_DIRECTIONS.contains(direction)) {
            throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': 'limit.direction' must be one of " + LIMIT_DIRECTIONS);
        }
        String field = text(limitNode, "field");
        if (isScalar) {
            if (field != null) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId
                    + "': a scalar variable orders by its own value — 'limit.field' isn't used");
            }
        } else if (PROJECT_SCOPED_ENTITIES.contains(variableType)) {
            Set<String> allowed = switch (variableType) {
                case "asset" -> ASSET_LIMIT_SORT_KEYS;
                case "finding" -> FINDING_LIMIT_SORT_KEYS;
                default -> DETECTION_LIMIT_SORT_KEYS;
            };
            if (field == null || !allowed.contains(field.toLowerCase())) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': 'limit.field' must be one of "
                    + allowed + " for variableType '" + variableType + "'");
            }
        } else {
            if (field == null) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId
                    + "': 'limit.field' is required when 'limit.strategy' is 'ORDER'");
            }
            EntityAqlRegistry<?> registry;
            try {
                registry = aqlRegistries.require(variableType);
            } catch (IllegalArgumentException e) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': " + e.getMessage());
            }
            AqlField<?> aqlField;
            try {
                aqlField = registry.requireField(field);
            } catch (RuntimeException e) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': " + e.getMessage());
            }
            if (!(aqlField instanceof PostgresColumnField)) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + nodeId + "': field '" + field
                    + "' on entity '" + variableType + "' can't be used to order results — only plain or array columns support this");
            }
        }
    }

    /** ENUM/PRIORITY are string-valued at the AQL surface; STRING_LIST is the type of the *field*
     *  as a whole (an array column) — once projected, each element is a plain string. */
    private static String scalarTypeOf(AqlField<?> field) {
        return switch (field.type()) {
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            default -> "string";
        };
    }

    /** Every entity the shared tag catalog (com.martecyber.ares.tags) currently attaches to. */
    private static final Set<String> TAGGABLE_ENTITIES = Set.of("asset", "detection", "finding", "exploit", "finding_template");

    /** ACTION_MANAGE_TAGS consumes an already-assigned ASSIGN_VARIABLE variable rather than its
     *  own inline AQL query — reuses that node's own entity-dispatch logic (asset/detection/
     *  finding via their scoped services, exploit/finding_template via the unscoped generic
     *  registry) with zero duplication, since the variable's items already carry the target ids.
     *  Needs the full graph (not just this node's own config) to cross-check the referenced
     *  variable actually exists and resolves to a taggable entity type. */
    private void validateManageTags(WorkflowGraph graph, String nodeId, JsonNode config) {
        String variableName = text(config, "variableName");
        if (variableName == null) {
            throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId + "' requires a 'variableName'");
        }
        WorkflowGraph.Node source = graph.nodes().stream()
            .filter(n -> WorkflowNodeType.ASSIGN_VARIABLE.equals(n.type())
                && variableName.equals(text(n.data() == null ? null : n.data().config(), "variableName")))
            .findFirst()
            .orElseThrow(() -> new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId
                + "': no ASSIGN_VARIABLE node in this workflow assigns a variable named '" + variableName + "'"));
        String variableType = text(source.data().config(), "variableType");
        if (!TAGGABLE_ENTITIES.contains(variableType)) {
            throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId + "': variable '" + variableName
                + "' holds '" + variableType + "', not a taggable entity (" + String.join(", ", TAGGABLE_ENTITIES) + ")");
        }

        List<Long> addTagIds = positiveLongArray(config, "addTagIds", nodeId);
        List<Long> removeTagIds = positiveLongArray(config, "removeTagIds", nodeId);
        if (addTagIds.isEmpty() && removeTagIds.isEmpty()) {
            throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId
                + "' requires at least one tag id in 'addTagIds' or 'removeTagIds'");
        }
    }

    /** ACTION_UPDATE_DETECTION_STATUS — same variable-based targeting as ACTION_MANAGE_TAGS
     *  above, but scoped to "detection" only (status is a detection-specific concept, unlike
     *  tags which several entity types share). The target 'status' name itself isn't checked
     *  against the detection_status catalog here — same call as {@link #validateManageTags}'s
     *  tag ids: existence is a runtime concern (DetectionService#updateStatus's own
     *  resolveStatus/validateTransition already reject an unknown or illegal-transition status
     *  with a clear error), not a save-time one. */
    private void validateUpdateDetectionStatus(WorkflowGraph graph, String nodeId, JsonNode config) {
        String variableName = text(config, "variableName");
        if (variableName == null) {
            throw new WorkflowValidationException("ACTION_UPDATE_DETECTION_STATUS node '" + nodeId + "' requires a 'variableName'");
        }
        WorkflowGraph.Node source = graph.nodes().stream()
            .filter(n -> WorkflowNodeType.ASSIGN_VARIABLE.equals(n.type())
                && variableName.equals(text(n.data() == null ? null : n.data().config(), "variableName")))
            .findFirst()
            .orElseThrow(() -> new WorkflowValidationException("ACTION_UPDATE_DETECTION_STATUS node '" + nodeId
                + "': no ASSIGN_VARIABLE node in this workflow assigns a variable named '" + variableName + "'"));
        String variableType = text(source.data().config(), "variableType");
        if (!"detection".equals(variableType)) {
            throw new WorkflowValidationException("ACTION_UPDATE_DETECTION_STATUS node '" + nodeId + "': variable '"
                + variableName + "' holds '" + variableType + "', not 'detection'");
        }

        String status = text(config, "status");
        if (status == null || status.isBlank()) {
            throw new WorkflowValidationException("ACTION_UPDATE_DETECTION_STATUS node '" + nodeId + "' requires a 'status'");
        }
    }

    private static List<Long> positiveLongArray(JsonNode config, String field, String nodeId) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId + "': '" + field + "' must be an array");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || item.asLong() <= 0) {
                throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + nodeId + "': '" + field + "' must contain only positive tag ids");
            }
            ids.add(item.asLong());
        }
        return ids;
    }

    /** LOOP — for each item held by an already-assigned ASSIGN_VARIABLE variable (0 or more), runs
     *  the real graph nodes wired to its {@code loop_body} edge once per item, with {@code
     *  {{loop.item.*}}}/{@code {{loop.index}}}/{@code {{loop.count}}} layered on top of the run's
     *  normal context (see {@link WorkflowRunService#advance}). The body's own shape (both
     *  outgoing handles present, feeds back into this node, stays self-contained) is checked in
     *  {@link #validateStructure} via {@link LoopBodyResolver} — this only needs the same
     *  variable-exists cross-check {@link #validateManageTags} already does, since the body no
     *  longer lives in this node's own config. */
    private void validateLoop(WorkflowGraph graph, String nodeId, JsonNode config) {
        String variableName = text(config, "variableName");
        if (variableName == null) {
            throw new WorkflowValidationException("LOOP node '" + nodeId + "' requires a 'variableName'");
        }
        graph.nodes().stream()
            .filter(n -> WorkflowNodeType.ASSIGN_VARIABLE.equals(n.type())
                && variableName.equals(text(n.data() == null ? null : n.data().config(), "variableName")))
            .findFirst()
            .orElseThrow(() -> new WorkflowValidationException("LOOP node '" + nodeId
                + "': no ASSIGN_VARIABLE node in this workflow assigns a variable named '" + variableName + "'"));
    }

    private void validateAgentTask(WorkflowGraph graph, String nodeId, JsonNode config, boolean requireComplete,
                                    List<WorkflowValidationWarning> warnings) {
        if (config == null) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "' requires 'poolId' and 'tool'");
        }
        String tool = text(config, "tool");
        if (tool == null) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "' requires 'poolId' and 'tool'");
        }
        JsonNode poolIdNode = config.get("poolId");
        boolean hasPoolId = poolIdNode != null && poolIdNode.isNumber();
        if (requireComplete && !hasPoolId) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "' requires 'poolId' and 'tool'");
        }
        JsonNode argsTemplate = config.get("argsTemplate");
        validateTargetsFrom(graph, nodeId, argsTemplate);

        // Best-effort only: argsTemplate may contain {{context}} placeholders that aren't real
        // values yet, so skip the spec's strict schema check whenever any are present — the real
        // check happens at run time once placeholders are resolved.
        if (argsTemplate != null && !containsTemplatePlaceholder(argsTemplate)) {
            Map<String, Object> args = MAPPER.convertValue(argsTemplate, Map.class);
            if (args.containsKey("targetsFrom")) {
                // targetsFrom (an asset_aql selector) only resolves into a real 'targets' list at
                // run time (TargetResolver, called from AgentTaskService.createAll) — stand in an
                // empty list so shape validation (which arg KEYS a tool needs) doesn't fail on a
                // key that's legitimately absent until then.
                args = new java.util.HashMap<>(args);
                args.remove("targetsFrom");
                args.putIfAbsent("targets", java.util.List.of());
            }
            try {
                agentToolSpecRegistry.validateArgs(tool, args);
            } catch (ResponseStatusException e) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': " + e.getReason());
            }
        }

        // maxTargets: 1 (wpscan, ffuf) is a hard invariant, not a warning — a node whose batchSize
        // isn't exactly 1 could hand the agent more than one target per task, which those tools
        // simply cannot run. Checked independently of argsTemplate's placeholder state above,
        // since batchSize is a plain node config value, never a template. Gated by requireComplete
        // like poolId/integrationId above — batchSize is a further-along config detail an operator
        // may not have reached yet in an in-progress draft.
        AgentToolSpec spec = agentToolSpecRegistry.find(tool).orElse(null);
        if (requireComplete && spec != null && spec.maxTargets() != null) {
            JsonNode batchSizeNode = config.get("batchSize");
            Integer batchSize = batchSizeNode != null && batchSizeNode.isNumber() ? batchSizeNode.asInt() : null;
            if (!Objects.equals(batchSize, spec.maxTargets())) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': tool '" + tool
                    + "' accepts at most " + spec.maxTargets() + " target(s) per task — set 'batchSize' to "
                    + spec.maxTargets());
            }
        }

        // Non-blocking: whether any agent in the pool CURRENTLY reports this tool is live,
        // dynamic state (an agent going offline doesn't make the workflow itself invalid) — never
        // a hard error, unlike everything else in this method. Skipped whenever poolId is absent
        // (a draft not yet requiring completeness) since there's no pool to check.
        if (hasPoolId && !poolNowSupportsTool(poolIdNode.asLong(), tool)) {
            warnings.add(new WorkflowValidationWarning(nodeId,
                "No agent in the selected pool currently reports '" + tool + "' as available — "
                    + "tasks for this node won't be claimed until one does"));
        }
    }

    /** True if at least one CURRENT member of {@code poolId} last reported {@code tool} in its
     *  heartbeat capabilities — same JSONB-array-of-{@code {tool,...}} shape {@code
     *  AgentTaskService#availableToolsFor} already parses for dispatch, duplicated here rather
     *  than shared since the two operate over different inputs (one agent vs. a whole pool). */
    private boolean poolNowSupportsTool(Long poolId, String tool) {
        List<AgentPoolMember> members = agentPoolMemberRepo.findByPoolId(poolId);
        if (members.isEmpty()) return false;
        List<Long> agentIds = members.stream().map(AgentPoolMember::getAgentId).toList();
        for (Agent a : agentRepo.findAllById(agentIds)) {
            String caps = a.getCapabilities();
            if (caps == null || caps.isBlank()) continue;
            try {
                List<Map<String, Object>> entries = MAPPER.readValue(caps, new TypeReference<>() {});
                for (Map<String, Object> e : entries) {
                    if (tool.equals(String.valueOf(e.get("tool")))) return true;
                }
            } catch (Exception ignored) { /* malformed/legacy capabilities — treat as none reported */ }
        }
        return false;
    }

    /** Parse-checks an {@code asset_aql} targetsFrom selector's AQL at save time (mirrors
     *  ASSIGN_VARIABLE's own parse-only validation — full resolution is a run-time concern, since
     *  it depends on live project data and possibly {{context}} placeholders), or cross-checks a
     *  {@code workflow_variable} selector's referenced variable the same way {@link
     *  #validateManageTags}/{@link #validateLoop} do — restricted to 'asset'-typed variables since
     *  {@code WorkflowRunService#resolveWorkflowVariableTargets} needs each item's own {@code
     *  identifier}/{@code type} fields, which only an asset-typed variable's items carry. Every
     *  other {@code targetsFrom.type} (scope_wildcards/scope_entries/project_assets) gets no
     *  save-time shape check here — same as before this method learned about workflow_variable. */
    private void validateTargetsFrom(WorkflowGraph graph, String nodeId, JsonNode argsTemplate) {
        if (argsTemplate == null) return;
        JsonNode targetsFrom = argsTemplate.get("targetsFrom");
        if (targetsFrom == null) return;
        String type = text(targetsFrom, "type");
        if ("asset_aql".equals(type)) {
            String aql = text(targetsFrom, "aql");
            if (aql == null || aql.isBlank()) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': targetsFrom.aql is required");
            }
            if (containsTemplatePlaceholder(targetsFrom)) return;
            try {
                AqlParser.parse(aql);
            } catch (AqlParseException e) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': " + e.getMessage());
            }
        } else if ("workflow_variable".equals(type)) {
            String variableName = text(targetsFrom, "variableName");
            if (variableName == null || variableName.isBlank()) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': targetsFrom.variableName is required");
            }
            WorkflowGraph.Node source = graph.nodes().stream()
                .filter(n -> WorkflowNodeType.ASSIGN_VARIABLE.equals(n.type())
                    && variableName.equals(text(n.data() == null ? null : n.data().config(), "variableName")))
                .findFirst()
                .orElseThrow(() -> new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId
                    + "': no ASSIGN_VARIABLE node in this workflow assigns a variable named '" + variableName + "'"));
            String variableType = text(source.data().config(), "variableType");
            if (!"asset".equals(variableType)) {
                throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': variable '" + variableName
                    + "' holds '" + variableType + "', not 'asset' — agent tasks can only target asset-typed variables");
            }
        }
    }

    private boolean containsTemplatePlaceholder(String s) {
        return s != null && s.contains("{{");
    }

    private boolean containsTemplatePlaceholder(JsonNode node) {
        if (node.isTextual()) return node.textValue().contains("{{");
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                if (containsTemplatePlaceholder(child)) return true;
            }
        }
        return false;
    }

    /** Deliberately checks against {@link IntegrationActionRegistry} rather than a hardcoded set
     *  (the retired ACTION_SYNC node type used to validate against exactly that kind of fixed
     *  capability set) — the whole point of ACTION_INTEGRATION_CALL is that a new action
     *  registered for an integration type becomes usable here with no validator change. */
    private void validateIntegrationCall(String scopeKind, Long scopeId, String nodeId, JsonNode config, boolean requireComplete) {
        if (config == null) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "' requires 'integrationType', 'integrationId' and 'action'");
        }
        String integrationType = text(config, "integrationType");
        String action = text(config, "action");
        if (integrationType == null || action == null) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "' requires 'integrationType', 'integrationId' and 'action'");
        }
        if (requireComplete) {
            JsonNode integrationIdNode = config.get("integrationId");
            boolean hasIntegrationId = integrationIdNode != null && integrationIdNode.isNumber();
            if (!hasIntegrationId) {
                throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "' requires 'integrationType', 'integrationId' and 'action'");
            }
        }
        // Checked before hasAction() so an unregistered type (almost always a not-installed/
        // not-enabled plugin now — see IntegrationActionRegistry#missingHandlerMessage) fails
        // with a message naming the plugin, not a misleading "unknown action" one.
        if (!integrationActionRegistry.isRegistered(integrationType)) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "': "
                + integrationActionRegistry.missingHandlerMessage(integrationType));
        }
        if (!integrationActionRegistry.hasAction(integrationType, action)) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "': unknown action '"
                + action + "' for integration type '" + integrationType + "'");
        }
        if (!integrationActionRegistry.supportsScope(integrationType, scopeKind)) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "': integration type '"
                + integrationType + "' isn't usable from a " + scopeKind + "-scoped workflow");
        }
        // scopeId null only for the legacy 3-arg validate() overload (see its own doc comment) —
        // real production saves always go through WorkflowService, which has a real scopeId.
        if (scopeId != null && !integrationActionRegistry.isAvailable(integrationType, scopeKind, scopeId)) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + nodeId + "': integration type '"
                + integrationType + "' isn't available for this " + scopeKind);
        }
    }

    /** A flat catalog of concrete, nameable event codes ({@code "<entity>.<action>"}) — not a
     *  generic {@code entityType}+{@code event} pair. The user explicitly asked for this after
     *  using the generic version: a single dropdown of specific things ("CVE added to KEV
     *  catalog", "CVE got a new PoC"), each free to carry its own payload shape, rather than every
     *  entity being limited to the same created/updated/deleted vocabulary. Still scope-gated the
     *  same way the old table was — each level only watches what actually lives at (or one level
     *  below) that level: a platform-wide admin workflow firing on *every* detection/finding/asset
     *  across every client would be enormous noise for little value, whereas an organization
     *  onboarding or a KB catalog entry changing are exactly the kind of low-frequency, structural
     *  events a platform-level workflow should watch. "cve" is the first "external database"
     *  entity — CWE/CAPEC/ATT&CK/OWASP intentionally not yet included: none of their sync
     *  pipelines have a per-document version/timestamp signal the way CVE's own lastModifiedAt
     *  does, so detecting created/updated for them needs its own per-entity diff work first. */
    static final Map<String, Set<String>> EVENT_CODES_BY_SCOPE = Map.of(
        WorkflowScope.PLATFORM, Set.of(
            "organization.created", "organization.updated",
            "finding_template.created", "finding_template.updated", "finding_template.deleted",
            "cve.created", "cve.updated", "cve.kev_added", "cve.poc_added"),
        WorkflowScope.ORGANIZATION, Set.of(
            "project.created", "project.updated", "project.deleted",
            "detection.created", "detection.updated", "detection.deleted",
            "finding.created", "finding.updated", "finding.deleted",
            "asset.created", "asset.updated", "asset.deleted"),
        WorkflowScope.PROJECT, Set.of(
            "detection.created", "detection.updated", "detection.deleted",
            "finding.created", "finding.updated", "finding.deleted",
            "asset.created", "asset.updated", "asset.deleted"));

    private void validateTriggerEvent(String scopeKind, String nodeId, JsonNode config) {
        String eventCode = text(config, "eventCode");
        Set<String> allowed = EVENT_CODES_BY_SCOPE.getOrDefault(scopeKind, Set.of());
        if (eventCode == null || !allowed.contains(eventCode)) {
            throw new WorkflowValidationException(
                "TRIGGER_EVENT node '" + nodeId + "' requires 'eventCode' to be one of " + allowed + " at " + scopeKind + " scope");
        }
    }

    /** Only shape validation here — vertical-scope enforcement and cycle/depth detection need a
     *  real DB lookup across separate workflow definitions, so they're necessarily a run-time-only
     *  concern (WorkflowRunService.executeCallWorkflow), same as ACTION_SYNC's capability-granted
     *  check is never pre-validated at save time either. Always a topic broadcast — one matching
     *  subscriber or several are the same code path, not separate modes. */
    private void validateCallWorkflow(String nodeId, JsonNode config) {
        String topic = text(config, "topic");
        if (topic == null || topic.isBlank()) {
            throw new WorkflowValidationException("ACTION_CALL_WORKFLOW node '" + nodeId + "' requires 'topic'");
        }
    }

    /** Shape only, mirroring ACTION_CALL_WORKFLOW's broadcast-mode 'topic' requirement — a
     *  workflow subscribes to a topic by name; WorkflowRunService.executeCallWorkflow matches
     *  broadcasts against every enabled TRIGGER_CALL_TOPIC trigger with the same topic string. */
    private void validateCallTopic(String nodeId, JsonNode config) {
        String topic = text(config, "topic");
        if (topic == null || topic.isBlank()) {
            throw new WorkflowValidationException("TRIGGER_CALL_TOPIC node '" + nodeId + "' requires 'topic'");
        }
    }

    /** Shape only: the URL must be present. Headers/bodyTemplate are optional and freeform
     *  (rendered as Mustache-style templates at run time, same as ACTION_NOTIFICATION's templates),
     *  and the signing secret (if any) lives out-of-band in outbound_webhook_secret, not here. */
    private void validateWebhookCall(String nodeId, JsonNode config) {
        String url = text(config, "url");
        if (url == null || url.isBlank()) {
            throw new WorkflowValidationException("ACTION_WEBHOOK_CALL node '" + nodeId + "' requires 'url'");
        }
    }

    private void validateCron(String nodeId, JsonNode config) {
        if (text(config, "cronExpression") == null) {
            throw new WorkflowValidationException("TRIGGER_CRON node '" + nodeId + "' requires 'cronExpression'");
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull() || !v.isTextual()) ? null : v.asText();
    }
}
