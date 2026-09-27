package com.martecyber.ares.workflows;

import java.util.Set;

/** {@code Node.type} values from the graph JSON schema (Workflows implementation plan). Every
 *  type is declared here from the start (so the frontend/validator vocabulary is stable) but
 *  rejected by {@code WorkflowGraphValidator} until its own phase wires an executor for it — see
 *  {@link #SUPPORTED_NODE_TYPES}. */
public final class WorkflowNodeType {
    public static final String TRIGGER_MANUAL = "TRIGGER_MANUAL";
    public static final String TRIGGER_CRON = "TRIGGER_CRON";
    public static final String TRIGGER_WEBHOOK = "TRIGGER_WEBHOOK";
    public static final String TRIGGER_EVENT = "TRIGGER_EVENT";
    public static final String TRIGGER_CALL_TOPIC = "TRIGGER_CALL_TOPIC";
    public static final String CONDITION = "CONDITION";
    public static final String ASSIGN_VARIABLE = "ASSIGN_VARIABLE";
    public static final String ACTION_AGENT_TASK = "ACTION_AGENT_TASK";
    public static final String ACTION_NOTIFICATION = "ACTION_NOTIFICATION";
    /** Retired — folded into ACTION_INTEGRATION_CALL (see DataSourceSyncIntegrationActionHandler
     *  and its Config class). No longer in SUPPORTED_NODE_TYPES; the constant stays only so
     *  SyncNodeMigration can still recognize and rewrite any already-saved graph that used it. */
    public static final String ACTION_SYNC = "ACTION_SYNC";
    public static final String ACTION_CALL_WORKFLOW = "ACTION_CALL_WORKFLOW";
    public static final String ACTION_WEBHOOK_CALL = "ACTION_WEBHOOK_CALL";
    public static final String ACTION_INTEGRATION_CALL = "ACTION_INTEGRATION_CALL";
    public static final String ACTION_MANAGE_TAGS = "ACTION_MANAGE_TAGS";
    /** Sets the status (e.g. "ignored") of every detection held by an already-assigned
     *  ASSIGN_VARIABLE variable (variableType "detection") — e.g. auto-ignoring detections an
     *  upstream CONDITION/AQL query matched. Reuses {@code DetectionService#updateStatus}
     *  unchanged, so the same {@code detection_status_transition} rules a manual status change
     *  enforces apply here too — an illegal transition fails the node rather than being silently
     *  skipped. See {@code WorkflowGraphValidator#validateUpdateDetectionStatus} and {@code
     *  WorkflowRunService#executeUpdateDetectionStatus}. */
    public static final String ACTION_UPDATE_DETECTION_STATUS = "ACTION_UPDATE_DETECTION_STATUS";
    /** Delivers a report for one finding — either generates a DOCX (reuses the same {@code
     *  ReportGenerationService} pipeline the manual "Generate document" flow uses, creating a
     *  single-finding {@code Report} row) or emails it via a KB {@code EmailTemplate} (see {@code
     *  FindingEmailReportService}) — the same Document-vs-Email choice available manually from
     *  {@code ProjectReportsView.vue}, now automatable from a workflow (e.g. triggered by {@code
     *  finding.created}). See {@code WorkflowRunService#executeReportFinding}. */
    public static final String ACTION_REPORT_FINDING = "ACTION_REPORT_FINDING";
    /** For-each over an already-assigned ASSIGN_VARIABLE variable's items (0 or more) — runs the
     *  real graph nodes wired to its {@code loop_body} outgoing edge once per item, with {@code
     *  {{loop.item.*}}}/{@code {{loop.index}}}/{@code {{loop.count}}} available on top of the run's
     *  normal context, closed by an edge the user draws from the end of that body back into this
     *  node; its {@code loop_done} edge fires once, after the last item (or immediately, if the
     *  variable is empty). An Operation, not an Action, alongside CONDITION/ASSIGN_VARIABLE — it
     *  has no side effect of its own, only its body does. Unlike every other node type, this one
     *  genuinely revisits itself and produces several {@code WorkflowStepRun} rows per run — see
     *  {@code IterationPath} and {@code WorkflowRunService#advance} for the per-iteration step-
     *  identity model this requires, and {@code com.martecyber.ares.workflows.graph.LoopBodyResolver}
     *  for how its body subgraph is resolved. The one sanctioned cycle in an otherwise-DAG-only
     *  graph — see {@code WorkflowGraphValidator#assertAcyclic}. */
    public static final String LOOP = "LOOP";
    /** A passive terminal node — no side effect of its own, just declares the run's final outcome
     *  (success/failure) once reached. Exists so a CONDITION's two required branches (see {@code
     *  WorkflowGraphValidator#validateStructure}) can both be satisfied cheaply — one branch's
     *  "nothing to do here" path doesn't need a real downstream action, just an END node — instead
     *  of a throwaway ASSIGN_VARIABLE/etc built purely to have somewhere for the edge to point.
     *  Always a dead end itself: {@code validateStructure} rejects any outgoing edge from one. */
    public static final String END = "END";

    public static final Set<String> TRIGGER_TYPES = Set.of(
        TRIGGER_MANUAL, TRIGGER_CRON, TRIGGER_WEBHOOK, TRIGGER_EVENT, TRIGGER_CALL_TOPIC);

    /** Node types the engine actually knows how to execute today — WorkflowGraphValidator rejects
     *  any other type at save time so a workflow can't be built around a not-yet-wired node.
     *  Grows one node type at a time as each phase of the implementation plan ships. */
    public static final Set<String> SUPPORTED_NODE_TYPES = Set.of(
        TRIGGER_MANUAL, TRIGGER_CRON, TRIGGER_WEBHOOK, TRIGGER_EVENT, TRIGGER_CALL_TOPIC, CONDITION, ASSIGN_VARIABLE,
        ACTION_AGENT_TASK, ACTION_NOTIFICATION, ACTION_CALL_WORKFLOW, ACTION_WEBHOOK_CALL,
        ACTION_INTEGRATION_CALL, ACTION_MANAGE_TAGS, ACTION_REPORT_FINDING, ACTION_UPDATE_DETECTION_STATUS, LOOP, END);

    public static boolean isTrigger(String type) {
        return TRIGGER_TYPES.contains(type);
    }

    private WorkflowNodeType() {}
}
