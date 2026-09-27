package com.martecyber.ares.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.CreateTask;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.TaskDto;
import com.martecyber.ares.aql.AqlQueryableEntityRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.compile.AqlInMemoryEvaluator;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.findings.templates.FindingTemplateService;
import com.martecyber.ares.integrations.notifications.EmailAddresses;
import com.martecyber.ares.integrations.notifications.MessagingIntegration;
import com.martecyber.ares.integrations.notifications.MessagingIntegrationRepository;
import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.MessagingService;
import com.martecyber.ares.integrations.notifications.MessagingTemplate;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.kb.exploits.ExploitService;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.reporting.FindingEmailReportService;
import com.martecyber.ares.reporting.ReportGenerationService;
import com.martecyber.ares.reporting.dto.GenerateReportRequest;
import com.martecyber.ares.reporting.dto.ReportDto;
import com.martecyber.ares.webhooks.WebhookSignatureService;
import com.martecyber.ares.workflows.graph.IterationPath;
import com.martecyber.ares.workflows.graph.LoopBodyResolver;
import com.martecyber.ares.workflows.graph.WorkflowGraph;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Workflow run state machine — see the Workflows implementation plan's "Execution engine"
 * section for the full design rationale. One tick of {@link #advance} repeatedly scans for nodes
 * whose predecessors have all resolved — always waited for in full, regardless of join mode,
 * since none of it can be decided before every relevant edge settles — and then either executes
 * or skip-propagates each one based on its join threshold (default: ALL relevant incoming edges
 * must have fired; a node can instead be configured for "at least N" — see {@link
 * #resolveJoinThreshold}), until either nothing more can progress in this pass (some step is
 * {@code waiting} on an async op, or the run is done) or an unhandled failure ends the run.
 * {@link WorkflowStepPoller} is what calls this again later once a {@code waiting} step's
 * referenced AgentTask/Job reaches a terminal state.
 *
 * <p><b>Compatibility note:</b> the join threshold's ALL default is a deliberate behavior change
 * from this engine's original "any one relevant edge taken" default — chosen knowingly even
 * though it means a node merging two mutually-exclusive CONDITION branches back together (the
 * classic if/else-then-continue pattern) will now never run unless explicitly configured with
 * {@code joinMode: "AT_LEAST"} and {@code joinCount: 1} on that merge node. Already-saved
 * workflows using that pattern need that explicit per-node fix — this was not treated as a bug
 * needing a compatibility shim.
 */
@Service
public class WorkflowRunService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowRunService.class);
    // registerModule(JavaTimeModule) — needed since the ASSIGN_VARIABLE redesign started
    // serializing raw KB entities (e.g. CveEntry.publishedAt is an Instant) straight into the run
    // context via MAPPER.valueToTree(...); a plain `new ObjectMapper()` doesn't know Instant by
    // default (unlike the Spring-managed one autoconfigured elsewhere in the app).
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final WorkflowRepository workflowRepo;
    private final WorkflowRunRepository runRepo;
    private final WorkflowStepRunRepository stepRunRepo;
    private final AqlRegistryLookup aqlRegistries;
    private final WorkflowEntityLookup entityLookup;
    private final MessagingService messagingService;
    private final MessagingIntegrationRepository messagingIntegrationRepo;
    private final AgentTaskService agentTaskService;
    private final AssetService assetService;
    private final FindingService findingService;
    private final DetectionService detectionService;
    private final ProjectRepository projectRepo;
    private final WorkflowTriggerRepository triggerRepo;
    private final WorkflowService workflowService;
    private final WebhookSignatureService webhookSignatureService;
    private final IntegrationActionRegistry integrationActionRegistry;
    private final AqlQueryableEntityRegistry aqlQueryableEntities;
    private final ExploitService exploitService;
    private final FindingTemplateService findingTemplateService;
    private final AgentToolSpecRegistry agentToolSpecRegistry;
    private final ReportGenerationService reportGenerationService;
    private final FindingEmailReportService findingEmailReportService;
    private final RestClient http = RestClient.create();

    // The field initializer matters for tests that construct this service directly via `new`
    // (bypassing Spring, so @Value never runs) — Spring's own @Value processing overwrites it
    // regardless once wired normally, so this is purely a same-as-the-property-default fallback.
    @org.springframework.beans.factory.annotation.Value("${ares.workflows.max-call-depth:10}")
    private int maxCallDepth = 10;

    /**
     * Self-reference so {@link #executeNode} can be invoked *through the Spring proxy* (see its
     * own doc comment for why it needs its own transaction) rather than via a plain {@code this.}
     * call, which — Spring's well-known self-invocation limitation — would silently skip its
     * {@code @Transactional} entirely. {@code @Lazy} on a *field* (not a constructor parameter)
     * sidesteps the real circular-construction problem: Spring finishes building this bean via its
     * normal constructor first, using every other (non-circular) dependency, and only resolves
     * this field — to a lazy proxy that defers to the by-then-fully-registered singleton — on
     * first actual use. {@code required = false} because every test in this codebase that
     * constructs this service directly via {@code new WorkflowRunService(...)} (bypassing Spring
     * entirely, same as every other field here) never populates it — {@link #advance} falls back
     * to plain {@code this} in that case, same un-isolated behavior this class had before this
     * field existed.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WorkflowRunService self;

    public WorkflowRunService(WorkflowRepository workflowRepo, WorkflowRunRepository runRepo,
                               WorkflowStepRunRepository stepRunRepo, AqlRegistryLookup aqlRegistries,
                               WorkflowEntityLookup entityLookup, MessagingService messagingService,
                               MessagingIntegrationRepository messagingIntegrationRepo,
                               AgentTaskService agentTaskService, AssetService assetService,
                               FindingService findingService, DetectionService detectionService,
                               ProjectRepository projectRepo, WorkflowTriggerRepository triggerRepo,
                               WorkflowService workflowService, WebhookSignatureService webhookSignatureService,
                               IntegrationActionRegistry integrationActionRegistry,
                               AqlQueryableEntityRegistry aqlQueryableEntities,
                               ExploitService exploitService, FindingTemplateService findingTemplateService,
                               AgentToolSpecRegistry agentToolSpecRegistry, ReportGenerationService reportGenerationService,
                               FindingEmailReportService findingEmailReportService) {
        this.workflowRepo = workflowRepo;
        this.runRepo = runRepo;
        this.stepRunRepo = stepRunRepo;
        this.aqlRegistries = aqlRegistries;
        this.entityLookup = entityLookup;
        this.messagingService = messagingService;
        this.messagingIntegrationRepo = messagingIntegrationRepo;
        this.agentTaskService = agentTaskService;
        this.projectRepo = projectRepo;
        this.assetService = assetService;
        this.findingService = findingService;
        this.detectionService = detectionService;
        this.triggerRepo = triggerRepo;
        this.workflowService = workflowService;
        this.webhookSignatureService = webhookSignatureService;
        this.integrationActionRegistry = integrationActionRegistry;
        this.aqlQueryableEntities = aqlQueryableEntities;
        this.exploitService = exploitService;
        this.findingTemplateService = findingTemplateService;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
        this.reportGenerationService = reportGenerationService;
        this.findingEmailReportService = findingEmailReportService;
    }

    /**
     * Deliberately NOT {@code @Transactional} itself anymore — it only orchestrates two separate,
     * independently-proxied calls ({@link #createRun} then {@link #advance}), each through {@code
     * self} so their own annotations actually apply (a bare {@code this.createRun(...)} would hit
     * Spring's self-invocation limitation and silently skip them). Splitting these into two
     * *sequential, separately-committing* transactions — instead of one big one wrapping
     * everything, which is what this method used to be — is required, not just a style choice:
     * {@link #executeNode} (called from inside {@link #advance}) runs in its OWN REQUIRES_NEW
     * transaction, on a genuinely separate DB connection, so it can only ever see rows another
     * transaction has already COMMITTED — if the {@code workflow_run} row were still sitting
     * uncommitted in this method's own transaction while {@code advance()}/{@code executeNode()}
     * tried to insert a {@code workflow_step_run} row referencing it, the foreign key check fails
     * outright ({@code workflow_step_run_workflow_run_id_fkey}, a real bug this fixes — the run
     * row has to be durably committed *before* any node execution can safely reference its id from
     * a different transaction).
     */
    public WorkflowRun start(Long workflowId, String triggerNodeId, Map<String, Object> triggerContext,
                              String triggeredBy, Long parentStepRunId) {
        return WorkflowSystemAuth.runAs(() -> {
            WorkflowRunService proxy = self != null ? self : this;
            Long runId = proxy.createRun(workflowId, triggerNodeId, triggerContext, triggeredBy, parentStepRunId);
            proxy.advance(runId);
            return runRepo.findById(runId).orElseThrow();
        });
    }

    /**
     * REQUIRES_NEW, deliberately — this is called synchronously and inline from entity-save paths
     * that already have their own transaction open (WorkflowEventDispatcher, fired straight from
     * DetectionService/FindingService/AssetService/etc. after a save — and those, in turn, are
     * sometimes called from ImportService's own big batch-import transaction). Plain REQUIRED
     * (the default) would have this JOIN that ambient transaction instead of opening its own —
     * so if anything inside a workflow run throws (a bad node config, an executor bug, anything),
     * Spring marks the *shared* physical transaction rollback-only before the exception ever
     * reaches WorkflowEventDispatcher's own try/catch. The catch still swallows the exception and
     * logs it, but by then the caller's transaction is already doomed: it can only fail later, at
     * its own commit, with a bare UnexpectedRollbackException — silently discarding an entire
     * import batch (or whatever else was in that transaction) with no direct error pointing at the
     * real cause. REQUIRES_NEW makes a workflow run's own persistence (and failure) fully
     * independent of whatever transaction happened to be open when it was triggered — matching
     * this class's own javadoc intent ("a workflow/notification failure must never poison the
     * caller's save transaction"), the same guarantee {@code MessagingDispatcher.onDetectionCreated}
     * gets for free by simply never being @Transactional in the first place.
     * <p>
     * Returns just the id (not the {@code WorkflowRun}, unlike {@link #start}) — {@code advance()}
     * always reloads the run fresh from the DB anyway, and returning the entity here would tempt a
     * caller into reusing a Hibernate-managed instance whose persistence context closed the moment
     * this REQUIRES_NEW transaction committed.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Long createRun(Long workflowId, String triggerNodeId, Map<String, Object> triggerContext,
                           String triggeredBy, Long parentStepRunId) {
        Workflow wf = workflowRepo.findById(workflowId).orElseThrow(() -> NotFoundException.of("workflow", workflowId));
        WorkflowGraph graph = parseGraph(wf.getGraphDefinition());
        WorkflowGraph.Node triggerNode = graph.nodes().stream()
            .filter(n -> n.id().equals(triggerNodeId)).findFirst()
            .orElseThrow(() -> new WorkflowValidationException("Unknown trigger node '" + triggerNodeId + "'"));
        if (!WorkflowNodeType.isTrigger(triggerNode.type())) {
            throw new WorkflowValidationException("Node '" + triggerNodeId + "' is not a trigger node");
        }

        OffsetDateTime now = OffsetDateTime.now();
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(wf.getId());
        run.setWorkflowVersion(wf.getVersion());
        run.setGraphSnapshot(wf.getGraphDefinition());
        run.setTriggerNodeId(triggerNodeId);
        run.setTriggeredBy(triggeredBy);
        run.setStatus(WorkflowRunStatus.RUNNING);
        run.setContext(writeJson(Map.of("trigger", triggerContext == null ? Map.of() : triggerContext)));
        run.setParentStepRunId(parentStepRunId);
        run.setStartedAt(now);
        run = runRepo.save(run);

        // The trigger itself is modeled as an already-COMPLETED step so advance()'s ordinary
        // predecessor-resolution logic seeds the graph's real first nodes — no separate
        // "trigger-seeding" code path needed.
        WorkflowStepRun triggerStep = new WorkflowStepRun();
        triggerStep.setWorkflowRunId(run.getId());
        triggerStep.setNodeId(triggerNodeId);
        triggerStep.setNodeType(triggerNode.type());
        triggerStep.setStatus(WorkflowStepStatus.COMPLETED);
        triggerStep.setStartedAt(now);
        triggerStep.setCompletedAt(now);
        stepRunRepo.save(triggerStep);

        return run.getId();
    }

    /**
     * REQUIRES_NEW, deliberately — not the plain {@code @Transactional} (REQUIRED) this used to
     * be. {@link #executeCallWorkflow} calls {@code start()} (and therefore, through {@code self},
     * this method) for each subscriber from WITHIN its own {@link #executeNode}'s already-open
     * REQUIRES_NEW transaction. Under REQUIRED, that call would just JOIN the parent step's still-
     * open transaction instead of running independently — meaning a child run's final {@code
     * COMPLETED}/{@code FAILED} status update (set at the bottom of this method, not through a
     * nested {@code executeNode} call, so nothing else isolates it) would sit uncommitted until
     * the *parent's* CALL_WORKFLOW step transaction eventually commits, and would be silently lost
     * entirely if anything anywhere in that outer transaction later fails or the process is
     * interrupted — every {@code WorkflowStepRun} row (including a reached END node's) already
     * durably committed via its own REQUIRES_NEW {@code executeNode} call, but the owning {@code
     * WorkflowRun} row stays stuck at {@code running} forever, with no later trigger to ever call
     * this method again. REQUIRES_NEW makes every {@code advance()} call — top-level or nested —
     * commit its own run's final status atomically and independently the moment it returns,
     * regardless of what transaction (if any) happened to be open when it was called.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void advance(Long runId) {
        // See WorkflowSystemAuth's own doc comment — advance() is the other choke point every
        // workflow execution funnels through (start() covers the initial call; this covers every
        // later resume — WorkflowStepPoller, a nested ACTION_CALL_WORKFLOW, etc.), so it gets the
        // same synthetic-auth fallback for whenever it's reached off a non-HTTP thread.
        WorkflowSystemAuth.runAs(() -> advanceInternal(runId));
    }

    private void advanceInternal(Long runId) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow(() -> NotFoundException.of("workflow run", runId));
        if (!WorkflowRunStatus.RUNNING.equals(run.getStatus())) return;

        WorkflowGraph graph = parseGraph(run.getGraphSnapshot());
        Map<String, WorkflowGraph.Node> nodesById = new HashMap<>();
        for (WorkflowGraph.Node n : graph.nodes()) nodesById.put(n.id(), n);
        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<WorkflowGraph.Edge>> incoming = new HashMap<>();
        for (WorkflowGraph.Edge e : graph.edges()) {
            outgoing.computeIfAbsent(e.source(), k -> new java.util.ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.target(), k -> new java.util.ArrayList<>()).add(e);
        }
        // Every LOOP node's own resolved body-node-id set, computed once per advance() call (the
        // graph itself never changes mid-run) — tells a given incoming edge into a LOOP node apart
        // as either its external "entry" edge (from outside the body) or one of its "feedback"
        // edges (from inside the body, closing an iteration). See IterationPath's own doc comment
        // and this method's inline comments below for the full per-iteration step-identity model.
        Map<String, Set<String>> loopBodyNodeIds = new HashMap<>();
        for (WorkflowGraph.Node n : graph.nodes()) {
            if (WorkflowNodeType.LOOP.equals(n.type())) {
                loopBodyNodeIds.put(n.id(), LoopBodyResolver.resolve(graph, n.id()).bodyNodeIds());
            }
        }

        Map<String, WorkflowStepRun> stepsByNode = new HashMap<>();
        for (WorkflowStepRun s : stepRunRepo.findByWorkflowRunId(runId)) {
            stepsByNode.put(compositeKey(s.getNodeId(), IterationPath.fromJson(s.getIterationPath())), s);
        }

        String unhandledFailureError = null;
        boolean progressed = true;
        while (progressed && unhandledFailureError == null) {
            progressed = false;

            // ---- candidate (node, path) discovery for this pass ----
            // Every path any existing step already lives at, plus two kinds of one-step-further
            // synthesis: "this LOOP node's very first gate, for every already-known scope" (a path
            // can't be discovered any other way — nothing points at it until it exists) and "the
            // next gate, for every LOOP step that already exists" (via increment). Nonsensical
            // combinations (e.g. a body node's id paired with an unrelated loop's path) simply
            // never resolve below and cost nothing but a skipped iteration.
            Set<IterationPath> candidatePaths = new LinkedHashSet<>();
            candidatePaths.add(IterationPath.ROOT);
            for (WorkflowStepRun s : stepsByNode.values()) candidatePaths.add(IterationPath.fromJson(s.getIterationPath()));
            List<IterationPath> knownSoFar = new ArrayList<>(candidatePaths);
            for (IterationPath p : knownSoFar) {
                for (WorkflowGraph.Node n : graph.nodes()) {
                    if (WorkflowNodeType.LOOP.equals(n.type())) candidatePaths.add(p.push(n.id(), 0));
                }
            }
            for (WorkflowStepRun s : stepsByNode.values()) {
                // Only a gate that actually said "there's another item" ever has a next gate to
                // synthesize — a SKIPPED gate (the loop's own entry was never taken) or a COMPLETED
                // one with hasNext=false (the terminal gate) has nothing further, and incrementing
                // it anyway would manufacture a phantom next-gate candidate that (since nothing
                // real ever backs it) itself resolves to SKIPPED — which, being a LOOP step too,
                // would get incremented again next pass, forever.
                if (WorkflowNodeType.LOOP.equals(s.getNodeType()) && WorkflowStepStatus.COMPLETED.equals(s.getStatus()) && readLoopHasNext(s)) {
                    candidatePaths.add(IterationPath.fromJson(s.getIterationPath()).incrementLast());
                }
            }

            // Every LOOP instance's terminal gate — SKIPPED (the loop's own entry edge was never
            // taken, so it never ran at all), COMPLETED with hasNext=false (ran out of items), or
            // FAILED (a structural problem with the loop itself, e.g. its variable was never set —
            // see executeLoop) — keyed by "loopNodeId@parentPath", i.e. exactly what a loop_done
            // edge's target needs to look up its real predecessor (which otherwise doesn't live at
            // the target's own candidate path the way every other edge's predecessor does).
            Map<String, WorkflowStepRun> finalGateByInstance = new HashMap<>();
            for (WorkflowStepRun s : stepsByNode.values()) {
                if (!WorkflowNodeType.LOOP.equals(s.getNodeType())) continue;
                IterationPath p = IterationPath.fromJson(s.getIterationPath());
                if (p.isRoot()) continue;
                String status = s.getStatus();
                boolean isTerminal = WorkflowStepStatus.FAILED.equals(status)
                    || (WorkflowStepStatus.SKIPPED.equals(status) && p.currentIndex() == 0)
                    || (WorkflowStepStatus.COMPLETED.equals(status) && !readLoopHasNext(s));
                if (isTerminal) {
                    finalGateByInstance.put(s.getNodeId() + "@" + p.popLast().canonical(), s);
                }
            }

            for (WorkflowGraph.Node node : graph.nodes()) {
                boolean isLoopNode = WorkflowNodeType.LOOP.equals(node.type());
                for (IterationPath path : candidatePaths) {
                    String key = compositeKey(node.id(), path);
                    if (stepsByNode.containsKey(key)) continue;
                    if (isLoopNode && (path.isRoot() || !node.id().equals(path.currentLoopNodeId()))) continue;
                    // Only the seeded trigger node has zero incoming edges anywhere in the graph —
                    // its step already exists (created by start(), before advance() ever runs) —
                    // so this is never actually about the trigger itself; it's what keeps a
                    // synthesized non-root candidate path from ever being tried against it (or any
                    // other zero-incoming node) as though it were some loop's body member.
                    if (!isLoopNode && incoming.getOrDefault(node.id(), List.of()).isEmpty()) continue;

                    // A later iteration's gate is also ready the instant an EARLIER iteration's body
                    // hit an unhandled failure (no error edge of its own) — continueOnError decides
                    // whether that just skips ahead to the next item or ends the whole loop right
                    // here, without ever waiting for a feedback edge that's never coming.
                    boolean shortCircuitReady = false;
                    if (isLoopNode && path.currentIndex() >= 1) {
                        IterationPath iterPath = path.decrementLast();
                        Optional<WorkflowStepRun> unhandled = findUnhandledFailureInIteration(stepsByNode.values(), outgoing, iterPath);
                        if (unhandled.isPresent()) {
                            if (readLoopContinueOnError(node)) {
                                shortCircuitReady = true;
                            } else {
                                unhandledFailureError = "LOOP node '" + node.id() + "': iteration " + iterPath.currentIndex()
                                    + " failed at node '" + unhandled.get().getNodeId() + "': " + unhandled.get().getError();
                                break;
                            }
                        }
                    }

                    boolean allResolved;
                    int takenCount = 0;
                    int relevantCount = 0;
                    if (shortCircuitReady) {
                        allResolved = true;
                    } else {
                        List<WorkflowGraph.Edge> relevant = relevantIncoming(node, path, incoming, loopBodyNodeIds);
                        // A LOOP node candidate with NO relevant incoming edge at all (e.g. a
                        // malformed graph with no feedback edge, or gate 0 of a LOOP with no
                        // external entry edge — both structurally rejected by the validator today,
                        // but this is still the engine's own safety net) must never be treated as
                        // "trivially resolved" the way a genuinely edge-less node elsewhere would
                        // be — that would SKIP this gate, and a skipped gate is still a LOOP step,
                        // so the next pass would synthesize gate+1 as a candidate again, forever.
                        if (isLoopNode && relevant.isEmpty()) continue;
                        allResolved = true;
                        relevantCount = relevant.size();
                        for (WorkflowGraph.Edge edge : relevant) {
                            WorkflowGraph.Node predNode = nodesById.get(edge.source());
                            // Where would the predecessor's OWN step live, from this candidate's
                            // point of view — unchanged for almost every edge; only a candidate
                            // that is ITSELF a LOOP node (this node) shifts it, via its own
                            // feedback (decrement) or external-entry (pop) rule. This applies
                            // whether the predecessor is a plain node or another LOOP closing via
                            // its own loop_done edge — e.g. a nested LOOP as the last thing in this
                            // one's body, whose loop_done edge IS this node's feedback edge.
                            IterationPath predPath = isLoopNode
                                ? (loopBodyNodeIds.getOrDefault(node.id(), Set.of()).contains(edge.source()) ? path.decrementLast() : path.popLast())
                                : path;
                            WorkflowStepRun predStep;
                            if (WorkflowNodeType.LOOP.equals(predNode.type()) && WorkflowGraph.HANDLE_LOOP_DONE.equals(edge.sourceHandle())) {
                                // The predecessor is itself a LOOP whose loop_done just fired — its
                                // own step doesn't live AT predPath (that's only true for its own
                                // gates, keyed one level deeper); it needs the terminal-gate lookup.
                                predStep = finalGateByInstance.get(edge.source() + "@" + predPath.canonical());
                            } else {
                                predStep = stepsByNode.get(compositeKey(edge.source(), predPath));
                            }
                            if (predStep == null || isActive(predStep.getStatus())) {
                                allResolved = false;
                                break;
                            }
                            if (isTaken(predStep, edge, predNode)) {
                                takenCount++;
                            }
                        }
                    }
                    if (!allResolved) continue;

                    progressed = true;
                    // Join threshold: how many of this node's relevant incoming edges must have
                    // actually fired (not just resolved) before the node itself runs instead of
                    // being skip-propagated — see resolveJoinThreshold's own doc for the default
                    // (ALL) and the per-node "at least N" override.
                    if (takenCount < resolveJoinThreshold(node, relevantCount)) {
                        stepsByNode.put(key, createSkippedStep(run, node, path));
                        continue;
                    }

                    WorkflowStepRun stepRun;
                    try {
                        stepRun = (self != null ? self : this).executeNode(run, node, path);
                    } catch (Exception e) {
                        // executeNode's own try/catch (see its doc comment) already handles the common
                        // case — but if the node's business logic called into another @Transactional
                        // service method that threw (e.g. DetectionService.countByAql), that call
                        // already marked executeNode's own (isolated, thanks to REQUIRES_NEW) transaction
                        // rollback-only before its catch block ever ran, so even ITS OWN attempt to save
                        // a FAILED step then fails too, with a bare UnexpectedRollbackException here. This
                        // is the fallback the user explicitly asked for: still record that the run
                        // launched and exactly where it broke, in THIS (separate, still-healthy) transaction.
                        log.error("Node '{}' (type {}) failed in workflow run {}, and its own attempt to persist that "
                            + "failure also failed (likely a poisoned transaction from a nested @Transactional call — "
                            + "see the ERROR log line just above this one for the real underlying cause): {}",
                            node.id(), node.type(), run.getId(), e.getMessage(), e);
                        stepRun = recordCrashedStep(run, node, path, e);
                    }
                    stepsByNode.put(key, stepRun);
                    // WAITING steps have no real output yet (any output set so far is just a
                    // preliminary ref like {"jobId": ...}) — the poller merges the real one once the
                    // step actually reaches a terminal state.
                    if (!WorkflowStepStatus.WAITING.equals(stepRun.getStatus())) {
                        mergeStepResultIntoContext(run, stepRun);
                    }
                    if (WorkflowStepStatus.FAILED.equals(stepRun.getStatus()) && isUnhandledFailure(stepRun, outgoing)) {
                        // A body node's own unhandled failure is deferred to the owning loop's next
                        // gate (the findUnhandledFailureInIteration check above) rather than ending
                        // the run immediately — that's what continueOnError actually governs. Only a
                        // root-scoped failure, or the LOOP node's own gate failing outright (a
                        // structural problem — e.g. its variable was never assigned — not a per-item
                        // one, so retrying further items would just fail identically forever), ends
                        // the run here.
                        if (path.isRoot() || isLoopNode) {
                            unhandledFailureError = stepRun.getError();
                        }
                    }
                }
                if (unhandledFailureError != null) break;
            }
        }

        if (unhandledFailureError != null) {
            run.setStatus(WorkflowRunStatus.FAILED);
            run.setError(unhandledFailureError);
            run.setCompletedAt(OffsetDateTime.now());
            runRepo.save(run);
            return;
        }

        boolean anyUnsettled = stepsByNode.values().stream().anyMatch(s -> isActive(s.getStatus()));
        if (!anyUnsettled) {
            String endFailureMessage = findEndFailureMessage(stepsByNode.values());
            if (endFailureMessage != null) {
                run.setStatus(WorkflowRunStatus.FAILED);
                run.setError(endFailureMessage);
            } else {
                run.setStatus(WorkflowRunStatus.COMPLETED);
            }
            run.setCompletedAt(OffsetDateTime.now());
            runRepo.save(run);
        }
        // else: some step is still WAITING/RUNNING — stays RUNNING; WorkflowStepPoller (or a
        // synchronous completion for CALL_WORKFLOW, once that ships) calls advance() again later.
    }

    private static String compositeKey(String nodeId, IterationPath path) {
        return nodeId + "@" + path.canonical();
    }

    /** Which of a candidate's statically-declared incoming edges actually apply to it — trivial
     *  (all of them, checked at the SAME path as the candidate) for every non-LOOP node, including
     *  a body node's own incoming {@code loop_body} edge from its owning LOOP (that edge's
     *  predecessor — the LOOP's own gate step for this iteration — genuinely lives at the same
     *  path as the body node itself, no transform needed). A LOOP node's own two kinds of incoming
     *  edge are mutually exclusive by gate index: gate 0 only ever becomes ready via an external
     *  entry edge (from outside the body); gate ≥1 only ever becomes ready via a feedback edge
     *  (from inside the body, closing the previous iteration). */
    /**
     * How many of a node's relevant incoming edges must have actually fired ({@link #isTaken})
     * for the node to run — otherwise it's skip-propagated, same as before this threshold existed.
     * Configurable per node via {@code data.config}: {@code joinMode} "ALL" (default, absent
     * config included) requires every relevant edge; {@code "AT_LEAST"} with a numeric
     * {@code joinCount} requires only that many (clamped to [1, relevantCount] — a 0 or negative
     * value would mean "always run even skip-propagated", which isn't a real choice this offers).
     *
     * LOOP nodes are deliberately excluded from this — always threshold 1, the original "any one
     * relevant edge taken" rule, regardless of any {@code joinMode} config. A LOOP gate's
     * "relevant" edges are its own entry/feedback edges (see {@link #relevantIncoming}), a
     * LOOP-specific mechanism this generic threshold was never designed against, and a gate
     * realistically only ever has one relevant edge anyway — reusing the frontend's node-config
     * panel for a threshold here would just risk the iteration engine for no real use case.
     * {@code relevantCount == 0} is the short-circuit-ready path (a later LOOP iteration made
     * ready by an earlier one's unhandled, continue-on-error'd failure) — always 0 so that
     * already-decided "run regardless" isn't second-guessed here.
     */
    private int resolveJoinThreshold(WorkflowGraph.Node node, int relevantCount) {
        if (relevantCount == 0) return 0;
        if (WorkflowNodeType.LOOP.equals(node.type())) return 1;
        JsonNode config = node.data() != null ? node.data().config() : null;
        if (config == null) return relevantCount;
        String mode = config.path("joinMode").asText("ALL");
        if ("AT_LEAST".equals(mode)) {
            int configured = config.path("joinCount").asInt(1);
            return Math.max(1, Math.min(configured, relevantCount));
        }
        return relevantCount; // "ALL" (default)
    }

    private List<WorkflowGraph.Edge> relevantIncoming(WorkflowGraph.Node node, IterationPath path,
            Map<String, List<WorkflowGraph.Edge>> incoming, Map<String, Set<String>> loopBodyNodeIds) {
        List<WorkflowGraph.Edge> all = incoming.getOrDefault(node.id(), List.of());
        if (!WorkflowNodeType.LOOP.equals(node.type())) return all;
        Set<String> bodyIds = loopBodyNodeIds.getOrDefault(node.id(), Set.of());
        boolean gateZero = path.currentIndex() == 0;
        List<WorkflowGraph.Edge> relevant = new ArrayList<>();
        for (WorkflowGraph.Edge e : all) {
            boolean isFeedback = bodyIds.contains(e.source());
            if (gateZero != isFeedback) relevant.add(e);
        }
        return relevant;
    }

    private boolean isUnhandledFailure(WorkflowStepRun step, Map<String, List<WorkflowGraph.Edge>> outgoing) {
        if (!WorkflowStepStatus.FAILED.equals(step.getStatus())) return false;
        return outgoing.getOrDefault(step.getNodeId(), List.of()).stream()
            .noneMatch(e -> WorkflowGraph.HANDLE_ERROR.equals(e.sourceHandle()));
    }

    /** Whether ANY step belonging to iteration {@code iterPath} (that path itself, or nested
     *  deeper inside it — a body containing its own nested loop) failed without an error edge of
     *  its own. Excludes the owning LOOP node's own gate step deliberately — see this class's
     *  {@code advance()} for why that's always immediately fatal rather than eligible for
     *  {@code continueOnError} (a structural problem, not a per-item one — retrying would just fail
     *  identically forever). */
    private Optional<WorkflowStepRun> findUnhandledFailureInIteration(java.util.Collection<WorkflowStepRun> steps,
            Map<String, List<WorkflowGraph.Edge>> outgoing, IterationPath iterPath) {
        for (WorkflowStepRun s : steps) {
            if (WorkflowNodeType.LOOP.equals(s.getNodeType())) continue;
            if (startsWithPrefix(IterationPath.fromJson(s.getIterationPath()), iterPath) && isUnhandledFailure(s, outgoing)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private boolean startsWithPrefix(IterationPath path, IterationPath prefix) {
        List<IterationPath.Segment> ps = path.segments();
        List<IterationPath.Segment> pre = prefix.segments();
        if (ps.size() < pre.size()) return false;
        for (int i = 0; i < pre.size(); i++) {
            if (!ps.get(i).equals(pre.get(i))) return false;
        }
        return true;
    }

    private boolean readLoopHasNext(WorkflowStepRun gateStep) {
        try {
            return MAPPER.readTree(gateStep.getOutput()).path("hasNext").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean readLoopContinueOnError(WorkflowGraph.Node loopNode) {
        JsonNode config = loopNode.data() == null ? null : loopNode.data().config();
        return config == null || !config.hasNonNull("continueOnError") || config.get("continueOnError").asBoolean(true);
    }

    /** An END node always completes as a step (it's a deliberate business signal, not a technical
     *  failure — see {@link #executeEnd}), so it never trips the unhandledFailureError short-
     *  circuit above; a {@code "failure"} result is only checked here, once every branch has
     *  finished running, so a failure signaled on one branch never cuts a sibling branch short. */
    private String findEndFailureMessage(java.util.Collection<WorkflowStepRun> steps) {
        for (WorkflowStepRun s : steps) {
            if (!WorkflowNodeType.END.equals(s.getNodeType()) || !WorkflowStepStatus.COMPLETED.equals(s.getStatus()) || s.getOutput() == null) {
                continue;
            }
            try {
                JsonNode output = MAPPER.readTree(s.getOutput());
                if ("failure".equals(output.path("result").asText())) {
                    return output.path("message").asText("Workflow ended in failure at node '" + s.getNodeId() + "'");
                }
            } catch (JsonProcessingException ignored) {
                // Corrupt output shouldn't happen for a node this service writes itself.
            }
        }
        return null;
    }

    /** pending/running/waiting all count as "not yet resolved" for edge-taken purposes — only a
     *  terminal status (completed/failed/skipped) tells us which outgoing edges actually fire. */
    private boolean isActive(String status) {
        return WorkflowStepStatus.PENDING.equals(status) || WorkflowStepStatus.RUNNING.equals(status)
            || WorkflowStepStatus.WAITING.equals(status);
    }

    private boolean isTaken(WorkflowStepRun predStep, WorkflowGraph.Edge edge, WorkflowGraph.Node predNode) {
        String status = predStep.getStatus();
        if (WorkflowStepStatus.SKIPPED.equals(status)) return false;
        if (WorkflowNodeType.CONDITION.equals(predNode.type()) && WorkflowStepStatus.COMPLETED.equals(status)) {
            boolean result = readConditionResult(predStep);
            String wantHandle = result ? WorkflowGraph.HANDLE_TRUE : WorkflowGraph.HANDLE_FALSE;
            return wantHandle.equals(edge.sourceHandle());
        }
        if (WorkflowNodeType.LOOP.equals(predNode.type()) && WorkflowStepStatus.COMPLETED.equals(status)) {
            boolean hasNext = readLoopHasNext(predStep);
            String wantHandle = hasNext ? WorkflowGraph.HANDLE_LOOP_BODY : WorkflowGraph.HANDLE_LOOP_DONE;
            return wantHandle.equals(edge.sourceHandle());
        }
        if (WorkflowStepStatus.COMPLETED.equals(status)) {
            return edge.sourceHandle() == null || WorkflowGraph.HANDLE_SUCCESS.equals(edge.sourceHandle());
        }
        if (WorkflowStepStatus.FAILED.equals(status)) {
            return WorkflowGraph.HANDLE_ERROR.equals(edge.sourceHandle());
        }
        return false;
    }

    private WorkflowStepRun createSkippedStep(WorkflowRun run, WorkflowGraph.Node node, IterationPath path) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkflowRunId(run.getId());
        step.setNodeId(node.id());
        step.setNodeType(node.type());
        step.setIterationPath(path.toJson());
        step.setStatus(WorkflowStepStatus.SKIPPED);
        OffsetDateTime now = OffsetDateTime.now();
        step.setStartedAt(now);
        step.setCompletedAt(now);
        return stepRunRepo.save(step);
    }

    /**
     * REQUIRES_NEW, deliberately, same reasoning as {@link #start}'s own doc comment — a node's
     * executor (executeCondition/executeAssignVariable/etc.) often calls into another
     * {@code @Transactional} service method (e.g. {@code DetectionService.countByAql}). If that
     * call throws, Spring marks whichever transaction it *joined* rollback-only before this
     * method's own catch below ever runs — without REQUIRES_NEW, that would be the whole run's
     * transaction (shared with {@link #advance}/{@link #start}), silently discarding the run row
     * and every already-completed step alongside this one failed node, with nothing but a bare
     * UnexpectedRollbackException to show for it. With REQUIRES_NEW, only THIS node's own
     * transaction is at risk — but that still means the catch below's own {@code
     * stepRunRepo.save(step)} can itself fail for the exact same reason (this transaction was
     * already poisoned before the catch ran). {@link #advance}'s own call site wraps this method
     * in a further try/catch for exactly that residual case, persisting the failure in ITS OWN
     * (separate, never-poisoned) transaction instead — see {@link #recordCrashedStep}. Must be
     * invoked through the Spring proxy ({@code self.executeNode(...)}, never a bare {@code
     * this.executeNode(...)}) or the annotation below is silently skipped entirely (Spring's
     * well-known self-invocation limitation).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public WorkflowStepRun executeNode(WorkflowRun run, WorkflowGraph.Node node, IterationPath path) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkflowRunId(run.getId());
        step.setNodeId(node.id());
        step.setNodeType(node.type());
        step.setIterationPath(path.toJson());
        step.setStatus(WorkflowStepStatus.RUNNING);
        step.setStartedAt(OffsetDateTime.now());
        try {
            switch (node.type()) {
                case WorkflowNodeType.CONDITION -> executeCondition(run, node, step);
                case WorkflowNodeType.ASSIGN_VARIABLE -> executeAssignVariable(run, node, step);
                case WorkflowNodeType.ACTION_NOTIFICATION -> executeNotification(run, node, step);
                case WorkflowNodeType.ACTION_AGENT_TASK -> executeAgentTask(run, node, step);
                case WorkflowNodeType.ACTION_INTEGRATION_CALL -> executeIntegrationCall(run, node, step);
                case WorkflowNodeType.ACTION_CALL_WORKFLOW -> executeCallWorkflow(run, node, step);
                case WorkflowNodeType.ACTION_WEBHOOK_CALL -> executeWebhookCall(run, node, step);
                case WorkflowNodeType.ACTION_MANAGE_TAGS -> executeManageTags(run, node, step);
                case WorkflowNodeType.ACTION_UPDATE_DETECTION_STATUS -> executeUpdateDetectionStatus(run, node, step);
                case WorkflowNodeType.ACTION_REPORT_FINDING -> executeReportFinding(run, node, step);
                case WorkflowNodeType.LOOP -> executeLoop(run, node, step, path);
                case WorkflowNodeType.END -> executeEnd(run, node, step);
                default -> throw new IllegalStateException("Unsupported node type in Phase A: " + node.type());
            }
        } catch (Exception e) {
            // Logged here, at the point of the REAL failure — not just stashed in step.error,
            // which won't survive if this transaction is already poisoned (see this method's own
            // doc comment) and the save below fails too. WorkflowValidationException is how every
            // "this node's config doesn't fit the data it actually got" case is signaled (missing
            // trigger binding, unset variable, malformed AQL, etc.) — a user authoring/config
            // mistake, not an engine bug, so it's a WARN with just the message (no stack trace:
            // the message already says exactly what's wrong, and a trace here is noise every time
            // the same misconfigured node fires again). Anything else is unexpected and stays
            // ERROR with the full trace.
            if (e instanceof WorkflowValidationException) {
                log.warn("Node '{}' (type {}) failed in workflow run {}: {}", node.id(), node.type(), run.getId(), e.getMessage());
            } else {
                log.error("Node '{}' (type {}) failed in workflow run {}: {}", node.id(), node.type(), run.getId(), e.getMessage(), e);
            }
            step.setStatus(WorkflowStepStatus.FAILED);
            step.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (!WorkflowStepStatus.WAITING.equals(step.getStatus())) {
            step.setCompletedAt(OffsetDateTime.now());
        }
        return stepRunRepo.save(step);
    }

    /** Fallback for when even {@link #executeNode}'s own graceful failure-handling couldn't
     *  persist itself (its transaction was already poisoned — see its doc comment). Called from
     *  {@link #advance}'s own (separate, still-healthy) transaction, so this save is never at risk
     *  of the same fate. */
    private WorkflowStepRun recordCrashedStep(WorkflowRun run, WorkflowGraph.Node node, IterationPath path, Exception e) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkflowRunId(run.getId());
        step.setNodeId(node.id());
        step.setNodeType(node.type());
        step.setIterationPath(path.toJson());
        step.setStatus(WorkflowStepStatus.FAILED);
        step.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        OffsetDateTime now = OffsetDateTime.now();
        step.setStartedAt(now);
        step.setCompletedAt(now);
        return stepRunRepo.save(step);
    }

    /** {@code mode} dispatch — see {@link WorkflowGraphValidator#validateCondition} for the
     *  save-time validation of both shapes. */
    private void executeCondition(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String mode = config.path("mode").asText(null);
        if (mode == null || "ENTITY_MATCH".equals(mode)) {
            executeConditionEntityMatch(run, node, step, config);
        } else {
            executeConditionCountCompare(run, node, step, config);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeConditionEntityMatch(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step, JsonNode config) {
        String entityType = textOrThrow(config, "entityType", node.id());
        String aql = textOrThrow(config, "aql", node.id());

        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        String resolvedAql = MessagingTemplate.render(aql, flatContext);

        Object entityIdVal = flatContext.get("trigger.entityId");
        Long entityId = entityIdVal instanceof Number n ? n.longValue() : null;
        Object entity = entityLookup.load(entityType, entityId)
            .orElseThrow(() -> new WorkflowValidationException(
                "CONDITION node '" + node.id() + "': no '" + entityType + "' bound in trigger context (need trigger.entityId)"));

        EntityAqlRegistry<Object> registry = (EntityAqlRegistry<Object>) aqlRegistries.require(entityType);
        AqlNode ast = AqlParser.parse(resolvedAql);
        boolean result = AqlInMemoryEvaluator.matches(entity, ast, registry);

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("result", result)));
    }

    /**
     * COUNT_COMPARE — resolves {@code left}/{@code right} each to a count (a query's matching row
     * count via {@link #resolveCount}, an already-assigned ASSIGN_VARIABLE variable's item count,
     * or a literal number) and compares them with {@code operator}. Unlike ENTITY_MATCH, this
     * needs no {@code trigger.entityId} binding at all — it's a query/variable operation, not a
     * single-entity check.
     */
    private void executeConditionCountCompare(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step, JsonNode config) {
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        long left = resolveCount(run, node, flatContext, config.get("left"), "left");
        long right = resolveCount(run, node, flatContext, config.get("right"), "right");
        String operator = textOrThrow(config, "operator", node.id());
        boolean result = compareCounts(left, operator, right);

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("result", result, "leftCount", left, "rightCount", right)));
    }

    private long resolveCount(WorkflowRun run, WorkflowGraph.Node node, Map<String, Object> flatContext, JsonNode operand, String side) {
        if (operand == null) {
            throw new WorkflowValidationException("CONDITION node '" + node.id() + "' (COUNT_COMPARE) requires '" + side + "'");
        }
        String kind = operand.path("kind").asText(null);
        return switch (kind == null ? "" : kind) {
            case "QUERY" -> {
                String entityType = operand.path("entityType").asText(null);
                String aql = MessagingTemplate.render(operand.path("aql").asText(""), flatContext);
                yield countByAql(run, node.id(), entityType, aql);
            }
            case "VARIABLE" -> {
                String variableName = operand.path("variableName").asText(null);
                JsonNode variableNode = readContextJson(run).path("variables").path(variableName);
                if (variableNode.isMissingNode() || variableNode.isNull()) {
                    throw new WorkflowValidationException("CONDITION node '" + node.id() + "' (COUNT_COMPARE." + side
                        + "): variable '" + variableName + "' isn't set yet — this node must run after the node that assigns it");
                }
                JsonNode items = variableNode.path("items");
                yield items.isArray() ? items.size() : 0;
            }
            case "LITERAL" -> operand.path("value").asLong();
            default -> throw new WorkflowValidationException("CONDITION node '" + node.id()
                + "' (COUNT_COMPARE." + side + "): unknown count operand kind '" + kind + "'");
        };
    }

    private long countByAql(WorkflowRun run, String nodeId, String entityType, String aql) {
        Workflow wf = workflowRepo.findById(run.getWorkflowId()).orElseThrow(() -> NotFoundException.of("workflow", run.getWorkflowId()));
        Long projectId = WorkflowScope.PROJECT.equals(wf.getScopeKind()) ? wf.getScopeId() : null;
        Long organizationId = WorkflowScope.ORGANIZATION.equals(wf.getScopeKind()) ? wf.getScopeId() : null;
        if (PROJECT_SCOPED_VARIABLE_ENTITIES.contains(entityType) && WorkflowScope.PLATFORM.equals(wf.getScopeKind())) {
            throw new WorkflowValidationException("CONDITION node '" + nodeId + "': entity '" + entityType
                + "' can't be queried from a platform-scoped workflow");
        }
        return switch (entityType) {
            case "asset" -> assetService.countByAql(organizationId, projectId, aql);
            case "finding" -> findingService.countByAql(projectId, organizationId, false, aql);
            case "detection" -> detectionService.countByAql(projectId, organizationId, aql);
            default -> aqlQueryableEntities.countEntities(entityType, aql);
        };
    }

    private boolean compareCounts(long left, String operator, long right) {
        return switch (operator) {
            case "EQ" -> left == right;
            case "NEQ" -> left != right;
            case "GT" -> left > right;
            case "GTE" -> left >= right;
            case "LT" -> left < right;
            case "LTE" -> left <= right;
            default -> throw new WorkflowValidationException("CONDITION: unknown operator '" + operator + "'");
        };
    }

    /** Cap on how many items a single ASSIGN_VARIABLE source pulls into the run's context — well
     *  under Asset's 500/Finding's & Detection's 200 hard caps, chosen so a workflow variable
     *  can't balloon the (JSON-in-a-column) run context unboundedly. */
    private static final int VARIABLE_QUERY_LIMIT = 200;

    private static final java.util.Set<String> PROJECT_SCOPED_VARIABLE_ENTITIES = java.util.Set.of("asset", "finding", "detection");

    /**
     * ASSIGN_VARIABLE (Operations group) — unlike CONDITION, which checks one already-bound
     * entity via {@link AqlInMemoryEvaluator}, this gathers a *list* of matches from one or more
     * inline AQL {@code sources} (each possibly against a different entity type — see {@link
     * WorkflowGraphValidator#validateAssignVariable} for the type-matching rule already enforced
     * at save time), combines them via {@code combineMode} when there's more than one, and stores
     * the result — always a list, however long, including empty — under {@code
     * context.variables.<name>}. {@code asset}/{@code finding}/{@code detection} sources go
     * through their own scoped {@code listByAql} (preserves org/project isolation, unchanged from
     * before this redesign); every other registered entity goes through {@link
     * AqlQueryableEntityRegistry}, which has no scope boundary to preserve (platform-wide
     * catalogs only) and additionally supports projecting a single {@code field} instead of the
     * whole entity, for scalar-typed variables.
     */
    private void executeAssignVariable(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String variableName = textOrThrow(config, "variableName", node.id());
        String variableType = textOrThrow(config, "variableType", node.id());

        Workflow wf = workflowRepo.findById(run.getWorkflowId()).orElseThrow(() -> NotFoundException.of("workflow", run.getWorkflowId()));
        Long projectId = WorkflowScope.PROJECT.equals(wf.getScopeKind()) ? wf.getScopeId() : null;
        Long organizationId = WorkflowScope.ORGANIZATION.equals(wf.getScopeKind()) ? wf.getScopeId() : null;
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());

        JsonNode limitNode = config.get("limit");
        // Only asset/finding/detection sources need the sort value precomputed at fetch time (see
        // #applyVariableLimit's doc comment for why) — every other item shape already carries
        // enough to resolve the field post-combine.
        String limitField = limitNode != null && limitNode.hasNonNull("field")
            ? limitNode.get("field").asText().toLowerCase() : null;

        List<List<Object>> sourceResults = new ArrayList<>();
        for (JsonNode source : config.get("sources")) {
            String entityType = textOrThrow(source, "entityType", node.id());
            String aql = MessagingTemplate.render(textOrThrow(source, "aql", node.id()), flatContext);
            if (PROJECT_SCOPED_VARIABLE_ENTITIES.contains(entityType) && WorkflowScope.PLATFORM.equals(wf.getScopeKind())) {
                throw new WorkflowValidationException("ASSIGN_VARIABLE node '" + node.id() + "': entity '"
                    + entityType + "' can't be queried from a platform-scoped workflow");
            }
            String field = source.hasNonNull("field") ? source.get("field").asText() : null;
            sourceResults.add(runAssignVariableSource(entityType, aql, field, projectId, organizationId, limitField));
        }

        List<Object> combined = combineAssignVariableSources(node.id(), config, sourceResults);
        combined = applyVariableLimit(combined, limitNode);

        mergeVariableIntoContext(run, variableName, variableType, combined);
        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("variableName", variableName, "variableType", variableType, "count", combined.size())));
    }

    private List<Object> runAssignVariableSource(String entityType, String aql, String field, Long projectId, Long organizationId, String limitField) {
        return switch (entityType) {
            case "asset" -> {
                Page<Asset> result = assetService.listByAql(organizationId, projectId, aql, null, null, 0, VARIABLE_QUERY_LIMIT);
                yield result.getContent().stream().map(a -> (Object) assetSummary(a, limitField)).toList();
            }
            case "finding" -> {
                Page<FindingDto> result = findingService.listByAql(projectId, organizationId, false, aql, null, null, 0, VARIABLE_QUERY_LIMIT);
                yield result.getContent().stream().map(f -> (Object) findingSummary(f, limitField)).toList();
            }
            case "detection" -> {
                Page<DetectionDto> result = detectionService.listByAql(projectId, organizationId, aql, null, null, 0, VARIABLE_QUERY_LIMIT);
                yield result.getContent().stream().map(d -> (Object) detectionSummary(d, limitField)).toList();
            }
            default -> field != null
                ? aqlQueryableEntities.queryProjected(entityType, aql, field, VARIABLE_QUERY_LIMIT)
                : aqlQueryableEntities.queryEntities(entityType, aql, VARIABLE_QUERY_LIMIT).stream()
                    .map(e -> (Object) MAPPER.valueToTree(e)).toList();
        };
    }

    /** Reserved key stashed into asset/finding/detection summary maps (never into the run
     *  context — {@link #applyVariableLimit} strips it before the final list is persisted) to
     *  carry the precomputed value of {@code limit.field}, resolved from the live DTO/entity at
     *  fetch time while it's still in hand — the summary maps {@link #assetSummary}/{@link
     *  #findingSummary}/{@link #detectionSummary} build discard everything else. */
    private static final String SORT_KEY = "_sortKey";

    /**
     * Optional post-combine cap on ASSIGN_VARIABLE's final deduped list (see {@link
     * WorkflowGraphValidator#validateAssignVariableLimit} for the save-time shape/field checks) —
     * applied once, after {@link #combineAssignVariableSources}, never per-source. {@code RANDOM}
     * shuffles then truncates; {@code ORDER} sorts by the resolved field's value then truncates.
     * Field resolution differs by item shape: asset/finding/detection summary maps carry a
     * precomputed {@link #SORT_KEY} (see {@link #runAssignVariableSource}); KB/catalog entities
     * are still a full {@link JsonNode} at this point, so the field is read straight off it; a
     * scalar-typed variable's items are the bare values themselves, ordered by themselves.
     */
    private List<Object> applyVariableLimit(List<Object> combined, JsonNode limitNode) {
        if (limitNode == null || limitNode.isNull()) return combined;
        int count = Math.max(limitNode.path("count").asInt(), 0);
        String strategy = limitNode.path("strategy").asText();
        List<Object> working = new ArrayList<>(combined);
        if ("RANDOM".equals(strategy)) {
            Collections.shuffle(working);
        } else {
            String field = limitNode.hasNonNull("field") ? limitNode.get("field").asText().toLowerCase() : null;
            boolean desc = "DESC".equals(limitNode.path("direction").asText("ASC"));
            Comparator<Object> cmp = (a, b) -> compareSortValues(extractSortValue(a, field), extractSortValue(b, field));
            working.sort(desc ? cmp.reversed() : cmp);
        }
        if (working.size() > count) {
            working = new ArrayList<>(working.subList(0, count));
        }
        for (Object item : working) {
            if (item instanceof Map<?, ?> map) map.remove(SORT_KEY);
        }
        return working;
    }

    private Object extractSortValue(Object item, String field) {
        if (item instanceof Map<?, ?> map) return map.get(SORT_KEY);
        if (item instanceof JsonNode node) return field != null ? jsonNodeToJava(node.get(field)) : null;
        return item;
    }

    private Object jsonNodeToJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    private int compareSortValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable<?> ca && ca.getClass().isInstance(b)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comp = (Comparable<Object>) ca;
            return comp.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** Union = concatenate + dedupe by {@link #dedupeKey}, first-occurrence-wins. Intersection =
     *  keep only items (from the first source, deduped) whose key is present in every other
     *  source. A single source needs no combining — {@code combineMode} isn't even required by
     *  the validator in that case. */
    private List<Object> combineAssignVariableSources(String nodeId, JsonNode config, List<List<Object>> sourceResults) {
        if (sourceResults.size() == 1) return sourceResults.get(0);
        String combineMode = textOrThrow(config, "combineMode", nodeId);
        return "intersection".equals(combineMode) ? intersectAssignVariableSources(sourceResults) : unionAssignVariableSources(sourceResults);
    }

    private List<Object> unionAssignVariableSources(List<List<Object>> sourceResults) {
        Map<Object, Object> merged = new LinkedHashMap<>();
        for (List<Object> items : sourceResults) {
            for (Object item : items) {
                merged.putIfAbsent(dedupeKey(item), item);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<Object> intersectAssignVariableSources(List<List<Object>> sourceResults) {
        Map<Object, Object> firstDeduped = new LinkedHashMap<>();
        for (Object item : sourceResults.get(0)) {
            firstDeduped.putIfAbsent(dedupeKey(item), item);
        }
        List<java.util.Set<Object>> otherKeySets = sourceResults.stream().skip(1)
            .map(items -> items.stream().map(this::dedupeKey).collect(java.util.stream.Collectors.toSet()))
            .toList();
        List<Object> result = new ArrayList<>();
        for (var entry : firstDeduped.entrySet()) {
            if (otherKeySets.stream().allMatch(keys -> keys.contains(entry.getKey()))) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /** Entity summaries (asset/finding/detection) and whole KB entities dedupe by {@code id};
     *  a projected scalar dedupes by its own value. */
    private Object dedupeKey(Object item) {
        if (item instanceof Map<?, ?> map && map.containsKey("id")) return map.get("id");
        if (item instanceof JsonNode jsonNode && jsonNode.has("id")) return jsonNode.get("id").asText();
        return item;
    }

    /**
     * END — always completes as a step; whether it makes the overall RUN succeed or fail is
     * decided afterward, once every branch has settled (see {@link #findEndFailureMessage}, called
     * from {@link #advance}) — not here. {@code message} (optional) is templated the same way
     * ACTION_NOTIFICATION's bodyTemplate is, so a failure message can reference {@code
     * {{trigger.detection.title}}}/{@code {{variables.x}}}/etc.
     */
    private void executeEnd(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String result = textOrThrow(config, "result", node.id());
        String message = config.hasNonNull("message") ? config.get("message").asText() : null;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result);
        if (message != null && !message.isBlank()) {
            Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
            output.put("message", MessagingTemplate.render(message, flatContext));
        }

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(output));
    }

    /**
     * ACTION_MANAGE_TAGS — adds/removes tags on the entities held by an already-assigned
     * ASSIGN_VARIABLE variable (see {@link WorkflowGraphValidator#validateManageTags} for the
     * save-time cross-check that the referenced variable exists and holds a taggable entity
     * type). Reads the variable's raw items straight out of {@code run.context} the same way
     * every other node that needs prior-context data does (no shared "read a variable's items"
     * accessor exists yet — see {@link #readContextJson}).
     */
    private void executeManageTags(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String variableName = textOrThrow(config, "variableName", node.id());

        JsonNode variableNode = readContextJson(run).path("variables").path(variableName);
        if (variableNode.isMissingNode() || variableNode.isNull()) {
            throw new WorkflowValidationException("ACTION_MANAGE_TAGS node '" + node.id() + "': variable '"
                + variableName + "' isn't set yet — this node must run after the node that assigns it");
        }
        String variableType = variableNode.path("variableType").asText(null);

        List<Long> targetIds = new ArrayList<>();
        for (JsonNode item : variableNode.path("items")) {
            if (item.has("id")) targetIds.add(item.get("id").asLong());
        }
        List<Long> addTagIds = longArray(config.get("addTagIds"));
        List<Long> removeTagIds = longArray(config.get("removeTagIds"));

        int tagsAdded = 0;
        int tagsRemoved = 0;
        for (Long targetId : targetIds) {
            for (Long tagId : addTagIds) {
                assignTagTo(variableType, targetId, tagId);
                tagsAdded++;
            }
            for (Long tagId : removeTagIds) {
                unassignTagFrom(variableType, targetId, tagId);
                tagsRemoved++;
            }
        }

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("variableType", variableType, "targetCount", targetIds.size(),
            "tagsAdded", tagsAdded, "tagsRemoved", tagsRemoved)));
    }

    /** Sane bound on how many items a single loop instance can process — items are read once per
     *  gate (see {@link #executeLoop}) so an unbounded variable would mean an unbounded number of
     *  gate steps. */
    private static final int LOOP_MAX_ITEMS = 500;
    private static final int LOOP_MAX_ERRORS_RECORDED = 50;

    /**
     * ACTION_UPDATE_DETECTION_STATUS — sets the status of every detection held by an already-
     * assigned ASSIGN_VARIABLE variable (see {@link WorkflowGraphValidator#validateUpdateDetectionStatus}
     * for the save-time cross-check that the referenced variable exists and holds "detection").
     * Reuses {@link DetectionService#updateStatus} unchanged per target — same transition
     * validation, history entry and event dispatch a manual status change gets. Not wrapped in a
     * try/catch per item (same choice as {@link #executeManageTags}): a target whose current
     * status can't legally reach the requested one fails the whole node with
     * DetectionService's own clear error, rather than silently skipping it.
     */
    private void executeUpdateDetectionStatus(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String variableName = textOrThrow(config, "variableName", node.id());
        String status = textOrThrow(config, "status", node.id());
        String note = config.path("note").asText(null);

        JsonNode variableNode = readContextJson(run).path("variables").path(variableName);
        if (variableNode.isMissingNode() || variableNode.isNull()) {
            throw new WorkflowValidationException("ACTION_UPDATE_DETECTION_STATUS node '" + node.id() + "': variable '"
                + variableName + "' isn't set yet — this node must run after the node that assigns it");
        }
        List<Long> targetIds = new ArrayList<>();
        for (JsonNode item : variableNode.path("items")) {
            if (item.has("id")) targetIds.add(item.get("id").asLong());
        }

        for (Long targetId : targetIds) {
            detectionService.updateStatus(targetId, status, note);
        }

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("status", status, "targetCount", targetIds.size())));
    }

    /**
     * LOOP — a "gate" node, revisited once per iteration attempt: {@code path}'s own innermost
     * segment is {@code (this node's id, the iteration index being decided)}; {@code
     * path.popLast()} is the scope this LOOP node itself sits in (its own predecessors' path).
     * The real body — whatever's wired to this node's {@code loop_body} edge — runs as ordinary
     * graph nodes, executing as their own separate steps (see {@link #advance}'s own doc comment
     * for the full per-iteration step-identity model this redesign introduced, and {@code
     * IterationPath}). This method only ever decides "is there another item, and if so overlay it
     * into the run's context for the body to read" (see {@link #persistLoopIterationContext}) —
     * every previous iteration's actual outcome, and {@code continueOnError}'s effect on it, is
     * handled entirely by {@link #advance} itself. On the final gate (no items left), aggregates
     * the whole instance's outcome (see {@link #aggregateLoopOutput}) into this step's output.
     */
    private void executeLoop(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step, IterationPath path) {
        int gateIndex = path.currentIndex();
        IterationPath parentPath = path.popLast();
        JsonNode config = node.data().config();
        String variableName = textOrThrow(config, "variableName", node.id());

        JsonNode variableNode = readContextJson(run).path("variables").path(variableName);
        if (variableNode.isMissingNode() || variableNode.isNull()) {
            throw new WorkflowValidationException("LOOP node '" + node.id() + "': variable '"
                + variableName + "' isn't set yet — this node must run after the node that assigns it");
        }
        List<JsonNode> items = new ArrayList<>();
        variableNode.path("items").forEach(items::add);
        if (items.size() > LOOP_MAX_ITEMS) {
            throw new WorkflowValidationException("LOOP node '" + node.id() + "': variable '" + variableName
                + "' has " + items.size() + " items, more than the " + LOOP_MAX_ITEMS + " a single loop can process");
        }

        if (gateIndex < items.size()) {
            persistLoopIterationContext(run, items.get(gateIndex), gateIndex, items.size());
            step.setOutput(writeJson(Map.of("hasNext", true, "index", gateIndex, "count", items.size())));
        } else {
            step.setOutput(writeJson(aggregateLoopOutput(run, node, parentPath, variableName, items.size())));
        }
        step.setStatus(WorkflowStepStatus.COMPLETED);
    }

    /** Overlays this iteration's {@code loop.item}/{@code loop.index}/{@code loop.count} into
     *  {@code run.context}, replacing any previous iteration's — real body nodes execute as their
     *  own separate {@link #executeNode} calls, possibly much later (e.g. after an
     *  ACTION_AGENT_TASK's WAITING step resumes), so unlike the old embedded-body design this has
     *  to be persisted, not just handed to an in-memory template call. Only the innermost enclosing
     *  loop's current item is addressable this way in this version — a nested loop's own gate
     *  overwrites the same flat {@code loop} key as it opens, so a deeply nested body node can't
     *  address an outer loop's item by name; a known v1 limitation. */
    private void persistLoopIterationContext(WorkflowRun run, JsonNode item, int index, int count) {
        JsonNode existing = readContextJson(run);
        ObjectNode root = existing.isObject() ? (ObjectNode) existing : MAPPER.createObjectNode();
        ObjectNode loopNode = MAPPER.createObjectNode();
        loopNode.set("item", item);
        loopNode.put("index", index);
        loopNode.put("count", count);
        root.set("loop", loopNode);
        run.setContext(writeJson(root));
        runRepo.save(run);
    }

    /** Tallies every iteration this loop instance actually ran (0..itemCount-1) into the same
     *  {@code itemCount}/{@code itemsSucceeded}/{@code itemsFailed}/{@code errors} shape the old
     *  embedded-body design produced, by re-checking each iteration's own step history for an
     *  unhandled failure (see {@link #findUnhandledFailureInIteration}) — cheap re-derivation
     *  instead of threading an accumulator through every gate, since every iteration's steps are
     *  already durably committed (their own separate REQUIRES_NEW transactions) by the time the
     *  final gate runs. */
    private Map<String, Object> aggregateLoopOutput(WorkflowRun run, WorkflowGraph.Node loopNode,
            IterationPath parentPath, String variableName, int itemCount) {
        WorkflowGraph graph = parseGraph(run.getGraphSnapshot());
        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        for (WorkflowGraph.Edge e : graph.edges()) outgoing.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
        List<WorkflowStepRun> allSteps = stepRunRepo.findByWorkflowRunId(run.getId());

        int itemsFailed = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int k = 0; k < itemCount; k++) {
            IterationPath iterPath = parentPath.push(loopNode.id(), k);
            Optional<WorkflowStepRun> failure = findUnhandledFailureInIteration(allSteps, outgoing, iterPath);
            if (failure.isEmpty()) continue;
            itemsFailed++;
            if (errors.size() < LOOP_MAX_ERRORS_RECORDED) {
                WorkflowStepRun f = failure.get();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", k);
                entry.put("nodeId", f.getNodeId());
                entry.put("message", f.getError());
                errors.add(entry);
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("hasNext", false);
        output.put("variableName", variableName);
        output.put("itemCount", itemCount);
        output.put("itemsSucceeded", itemCount - itemsFailed);
        output.put("itemsFailed", itemsFailed);
        output.put("errors", errors);
        return output;
    }

    private void assignTagTo(String entityType, Long targetId, Long tagId) {
        switch (entityType) {
            case "asset" -> assetService.assignTag(targetId, tagId);
            case "detection" -> detectionService.assignTag(targetId, tagId);
            case "finding" -> findingService.assignTag(targetId, findingService.get(targetId).projectId(), tagId);
            case "exploit" -> exploitService.assignTag(targetId, tagId);
            case "finding_template" -> findingTemplateService.assignTag(targetId, tagId);
            default -> throw new WorkflowValidationException("ACTION_MANAGE_TAGS: unsupported entity '" + entityType + "'");
        }
    }

    private void unassignTagFrom(String entityType, Long targetId, Long tagId) {
        switch (entityType) {
            case "asset" -> assetService.unassignTag(targetId, tagId);
            case "detection" -> detectionService.unassignTag(targetId, tagId);
            case "finding" -> findingService.unassignTag(targetId, findingService.get(targetId).projectId(), tagId);
            case "exploit" -> exploitService.unassignTag(targetId, tagId);
            case "finding_template" -> findingTemplateService.unassignTag(targetId, tagId);
            default -> throw new WorkflowValidationException("ACTION_MANAGE_TAGS: unsupported entity '" + entityType + "'");
        }
    }

    private List<Long> longArray(JsonNode arrayNode) {
        List<Long> ids = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) ids.add(item.asLong());
        }
        return ids;
    }

    private Map<String, Object> assetSummary(Asset a, String limitField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("code", a.getCode());
        m.put("identifier", a.getIdentifier());
        m.put("type", a.getType());
        if (limitField != null) m.put(SORT_KEY, assetSortValue(a, limitField));
        return m;
    }

    private Object assetSortValue(Asset a, String field) {
        return switch (field) {
            case "identifier" -> a.getIdentifier();
            case "type" -> a.getType();
            case "code" -> a.getCode();
            default -> a.getCreatedAt();
        };
    }

    private Map<String, Object> findingSummary(FindingDto f, String limitField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("code", f.code());
        m.put("title", f.title());
        m.put("severity", f.severity());
        if (limitField != null) m.put(SORT_KEY, findingSortValue(f, limitField));
        return m;
    }

    private Object findingSortValue(FindingDto f, String field) {
        return switch (field) {
            case "title" -> f.title();
            case "priority" -> f.severity();
            case "duedate" -> f.dueDate();
            case "reportedat" -> f.reportedAt();
            case "updatedat" -> f.updatedAt();
            default -> f.createdAt();
        };
    }

    private Map<String, Object> detectionSummary(DetectionDto d, String limitField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("title", d.title());
        m.put("severity", d.severity());
        m.put("status", d.status());
        m.put("assetIdentifier", d.assetIdentifier());
        if (limitField != null) m.put(SORT_KEY, detectionSortValue(d, limitField));
        return m;
    }

    private Object detectionSortValue(DetectionDto d, String field) {
        return switch (field) {
            case "severity" -> d.severity();
            case "title" -> d.title();
            case "lastseen" -> d.lastSeen();
            case "status" -> d.status();
            case "source" -> d.sourceType();
            default -> d.createdAt();
        };
    }

    private JsonNode readContextJson(WorkflowRun run) {
        try {
            return MAPPER.readTree(run.getContext());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt workflow run context JSON", e);
        }
    }

    private void mergeVariableIntoContext(WorkflowRun run, String variableName, String variableType, List<Object> items) {
        JsonNode existing = readContextJson(run);
        ObjectNode root = existing.isObject() ? (ObjectNode) existing : MAPPER.createObjectNode();
        ObjectNode variablesNode = root.has("variables") && root.get("variables").isObject()
            ? (ObjectNode) root.get("variables")
            : root.putObject("variables");
        ObjectNode varNode = MAPPER.createObjectNode();
        varNode.put("variableType", variableType);
        varNode.set("items", MAPPER.valueToTree(items));
        variablesNode.set(variableName, varNode);
        run.setContext(writeJson(root));
        runRepo.save(run);
    }

    /** Merges a just-terminal step's output/error into {@code run.context} under {@code
     *  steps.<nodeId>}, so a later node's config can reference {@code {{steps.<nodeId>.output.
     *  <field>}}}/{@code {{steps.<nodeId>.error}}} — {@link WorkflowContextFlattener} (and
     *  therefore every templated field in this class) only ever reads {@code run.context}, never
     *  queries {@code WorkflowStepRun} rows directly, so without this a downstream reference to
     *  another step's output silently never resolved (found live: a two-webhook-call workflow's
     *  context stayed exactly {@code {"trigger": {}}} after the first step completed). Skipped
     *  steps carry nothing worth exposing. Persists immediately, mirroring {@link
     *  #mergeVariableIntoContext} — a step that finishes asynchronously (via {@link
     *  WorkflowStepPoller}) is merged by a *separate*, later {@code advance()} call that
     *  re-fetches the run fresh from DB, so an in-memory-only mutation here wouldn't survive to
     *  reach it. */
    private void mergeStepResultIntoContext(WorkflowRun run, WorkflowStepRun step) {
        if (WorkflowStepStatus.SKIPPED.equals(step.getStatus())) return;
        JsonNode existing = readContextJson(run);
        ObjectNode root = existing.isObject() ? (ObjectNode) existing : MAPPER.createObjectNode();
        ObjectNode stepsNode = root.has("steps") && root.get("steps").isObject()
            ? (ObjectNode) root.get("steps")
            : root.putObject("steps");
        ObjectNode stepNode = MAPPER.createObjectNode();
        if (step.getOutput() != null) {
            try {
                stepNode.set("output", MAPPER.readTree(step.getOutput()));
            } catch (JsonProcessingException e) {
                stepNode.put("output", step.getOutput());
            }
        }
        if (step.getError() != null) {
            stepNode.put("error", step.getError());
        }
        stepsNode.set(step.getNodeId(), stepNode);
        run.setContext(writeJson(root));
        runRepo.save(run);
    }

    /** Public entry point for {@link WorkflowStepPoller}, which only has the step (and its
     *  {@code workflowRunId}) in hand, not an already-loaded {@link WorkflowRun}. */
    public void mergeStepResultIntoContext(Long runId, WorkflowStepRun step) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow(() -> NotFoundException.of("workflow run", runId));
        mergeStepResultIntoContext(run, step);
    }

    private void executeNotification(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        sendNotificationCore(config, flatContext, "ACTION_NOTIFICATION node '" + node.id() + "'");
        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("sent", true)));
    }

    /** Document mode reuses the exact same two-step pipeline the manual "Generate document" flow
     *  uses ({@code ReportGenerationService#create} then {@code #generateDocument}), just with a
     *  single resolved finding id instead of an operator's selection — same reason the manual
     *  Email mode and this node's email mode share {@code FindingEmailReportService}. */
    private void executeReportFinding(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        String errorLabel = "ACTION_REPORT_FINDING node '" + node.id() + "'";

        String findingIdTemplate = textOrThrow(config, "findingId", node.id());
        long findingId;
        try {
            findingId = Long.parseLong(MessagingTemplate.render(findingIdTemplate, flatContext).trim());
        } catch (NumberFormatException e) {
            throw new WorkflowValidationException(errorLabel + ": 'findingId' did not resolve to a number");
        }

        String mode = textOrThrow(config, "mode", node.id());
        Map<String, Object> output;
        if ("document".equals(mode)) {
            JsonNode templateIdNode = config.get("reportTemplateId");
            if (templateIdNode == null || !templateIdNode.isNumber()) {
                throw new WorkflowValidationException(errorLabel + " requires 'reportTemplateId'");
            }
            Long templateId = templateIdNode.asLong();
            FindingDto finding = findingService.get(findingId);
            Long organizationId = finding.projectId() != null
                ? projectRepo.findById(finding.projectId()).map(Project::getOrganizationId).orElse(null)
                : null;
            ReportDto report = reportGenerationService.create(new GenerateReportRequest(
                finding.projectId(), organizationId, templateId, null, List.of(findingId), null, null, Map.of()));
            reportGenerationService.generateDocument(report.id(), templateId);
            output = Map.of("reportId", report.id());
        } else if ("email".equals(mode)) {
            JsonNode emailTemplateIdNode = config.get("emailTemplateId");
            JsonNode integrationIdNode = config.get("integrationId");
            if (emailTemplateIdNode == null || !emailTemplateIdNode.isNumber()
                || integrationIdNode == null || !integrationIdNode.isNumber()) {
                throw new WorkflowValidationException(errorLabel + " requires 'emailTemplateId' and 'integrationId'");
            }
            List<String> to = renderAddresses(config, "to", flatContext, errorLabel);
            List<String> cc = renderAddresses(config, "cc", flatContext, errorLabel);
            List<String> bcc = renderAddresses(config, "bcc", flatContext, errorLabel);
            findingEmailReportService.sendEmailForFinding(
                findingId, emailTemplateIdNode.asLong(), integrationIdNode.asLong(), to, cc, bcc);
            output = Map.of("sent", true);
        } else {
            throw new WorkflowValidationException(errorLabel + ": 'mode' must be 'document' or 'email'");
        }

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(output));
    }

    /** {@code flatContext} is the caller's choice of what {@code {{...}}} resolves against — always
     *  the run's own flattened context, which already carries {@code loop.*} once a LOOP node has
     *  overlaid it (see {@link #persistLoopIterationContext}) — so this has no opinion on where it
     *  came from. */
    private void sendNotificationCore(JsonNode config, Map<String, Object> flatContext, String errorLabel) {
        JsonNode integrationIdNode = config == null ? null : config.get("integrationId");
        if (integrationIdNode == null || !integrationIdNode.isNumber()) {
            throw new WorkflowValidationException(errorLabel + " requires 'integrationId'");
        }
        MessagingIntegration integration = messagingIntegrationRepo.findById(integrationIdNode.asLong())
            .orElseThrow(() -> NotFoundException.of("messaging integration", integrationIdNode.asLong()));

        String severity = text(config, "severity");
        boolean isEmail;
        try { isEmail = MessagingKind.parse(integration.getKind()) == MessagingKind.EMAIL; }
        catch (Exception e) { isEmail = false; }

        // Recipients are per-send (unlike every other transport, where the destination lives in
        // the integration's own config) — only meaningful, and only rendered/validated, for
        // email-kind integrations; the fields are simply absent from every other node config.
        // ACTION_NOTIFICATION is a plain chat-style notification for every transport including
        // email — title + Markdown body, same as Discord/Slack/Teams. Rich HTML report content
        // lives in ACTION_REPORT_FINDING instead (see FindingEmailReportService), which is what
        // actually needs a KB EmailTemplate.
        List<String> to = isEmail ? renderAddresses(config, "to", flatContext, errorLabel) : List.of();
        List<String> cc = isEmail ? renderAddresses(config, "cc", flatContext, errorLabel) : List.of();
        List<String> bcc = isEmail ? renderAddresses(config, "bcc", flatContext, errorLabel) : List.of();

        String title = renderTemplateField(config, "titleTemplate", flatContext, "Workflow notification");
        // bodyTemplate is authored in ares-ui's MarkdownEditor.vue — render substituted values
        // escaped for CommonMark (so e.g. an asset identifier's underscore can't be misread as
        // emphasis) and flag the message as markdown so each sender converts it to its own
        // native rich-text syntax (see NotificationMarkdownRenderer).
        String body = config != null && config.has("bodyTemplate") && config.get("bodyTemplate").isTextual()
            ? MessagingTemplate.renderMarkdownSafe(config.get("bodyTemplate").asText(), flatContext)
            : "";

        messagingService.send(integration, new NotificationMessage(title, body, severity, null, true, to, cc, bcc), true);
    }

    /** Renders {@code field}'s raw comma/semicolon/newline-separated address list against
     *  {@code flatContext} and validates each entry — a bad address fails the step with a clear
     *  message instead of a silent partial send. Returns an empty list when the field is absent
     *  (To/CC/BCC are all optional except at least one To is required at delivery time — enforced
     *  by EmailSender, not here, since a webhook-kind node reusing the same config shape should
     *  never hit this validation at all). */
    private List<String> renderAddresses(JsonNode config, String field, Map<String, Object> flatContext, String errorLabel) {
        if (config == null || !config.has(field) || !config.get(field).isTextual()) return List.of();
        try {
            return EmailAddresses.renderAndValidate(field, config.get(field).asText(), flatContext);
        } catch (IllegalArgumentException e) {
            throw new WorkflowValidationException(errorLabel + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void executeAgentTask(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        JsonNode poolIdNode = config == null ? null : config.get("poolId");
        String tool = textOrThrow(config, "tool", node.id());
        if (poolIdNode == null || !poolIdNode.isNumber()) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + node.id() + "' requires 'poolId'");
        }
        String format = config.has("format") && config.get("format").isTextual() ? config.get("format").asText() : "default";
        Integer timeoutMinutes = config.has("timeoutMinutes") && config.get("timeoutMinutes").isNumber()
            ? config.get("timeoutMinutes").asInt() : null;
        // Optional — see AgentTaskService.createAll's own doc comment: when set, a selector
        // resolving to more targets than TargetResolver's MAX_TARGETS cap is split into several
        // tasks instead of failing outright, same as AgentTaskFormDialog.vue's own "Max targets
        // per batch" field for a manually-created task. Previously always null here, so a
        // workflow's ACTION_AGENT_TASK node had no way to opt into batching at all.
        Integer batchSize = config.has("batchSize") && config.get("batchSize").isNumber()
            ? config.get("batchSize").asInt() : null;
        // Same two rules-of-engagement fields AgentTaskFormDialog.vue collects from the operator
        // for a manually-created task — see EngagementRuleEnforcer#applyToTaskArgs, which every
        // AgentTaskService.createAll() caller (manual, schedule, workflow) funnels through
        // identically for header/rate-limit/concurrency injection and the time-window check.
        // nacProfile is just a vantage-point tag, safe to default to null like every other
        // optional CreateTask field. bypassTimeWindow defaults to false (never bypass) exactly
        // like AgentTaskScheduleService's recurring schedules — no human is present at run time to
        // make that call — but unlike a schedule, a workflow author CAN pre-declare a specific
        // node as bypass-safe (e.g. a passive tool) at config/review time, the same way an
        // operator ticks the checkbox at submission time.
        String nacProfile = config.has("nacProfile") && config.get("nacProfile").isTextual() && !config.get("nacProfile").asText().isBlank()
            ? config.get("nacProfile").asText() : null;
        boolean bypassTimeWindow = config.has("bypassTimeWindow") && config.get("bypassTimeWindow").asBoolean(false);

        Workflow wf = workflowRepo.findById(run.getWorkflowId()).orElseThrow(() -> NotFoundException.of("workflow", run.getWorkflowId()));
        if (!WorkflowScope.PROJECT.equals(wf.getScopeKind())) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK requires a project-scoped workflow");
        }
        Long projectId = wf.getScopeId();

        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        JsonNode argsTemplate = config.get("argsTemplate");
        Map<String, Object> args = argsTemplate == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>((Map<String, Object>) MAPPER.convertValue(renderJsonTemplate(argsTemplate, flatContext), Map.class));
        resolveWorkflowVariableTargets(run, node.id(), tool, args);

        CreateTask req = new CreateTask(null, poolIdNode.asLong(), tool, format, args, nacProfile, null, null, batchSize, bypassTimeWindow, timeoutMinutes);
        Long createdBy = parseLongOrNull(run.getTriggeredBy());
        List<TaskDto> created = agentTaskService.createAll(projectId, req, createdBy);
        TaskDto task = created.get(0);

        step.setStatus(WorkflowStepStatus.WAITING);
        step.setRefType("AGENT_TASK");
        step.setRefId(task.id());
        step.setOutput(writeJson(Map.of("taskId", task.id())));
    }

    /** {@code targetsFrom.type == "workflow_variable"} is resolved HERE, not by {@link
     *  com.martecyber.ares.agents.tasks.TargetResolver} — that resolver only ever sees
     *  project-wide data (assets/scope entries queried fresh from the DB) and has no notion of a
     *  workflow run's own {@code variables.*} context, and every other caller of it (manual task
     *  creation, schedules) has no workflow run at all to read one from. So this selector is
     *  converted into a literal {@code targets} list right here, before the {@link CreateTask}
     *  request is built — every other {@code targetsFrom.type} keeps flowing through {@code args}
     *  untouched, exactly as before, for {@code TargetResolver} to resolve downstream.
     *  Automatically restricts to the tool's own {@link
     *  AgentToolSpec#validAssetTypes()} (empty there means "no restriction") —
     *  this IS the "system automatically picks the compatible assets" behavior: the operator
     *  never manually declares which asset types apply, unlike {@code asset_aql}'s selector (see
     *  {@code NodeConfigPanel.vue}'s {@code selectedToolAssetTypes}), which still needs it spelled
     *  out because {@code TargetResolver} composes it straight into the AQL query it runs. */
    @SuppressWarnings("unchecked")
    private void resolveWorkflowVariableTargets(WorkflowRun run, String nodeId, String tool, Map<String, Object> args) {
        Object targetsFromObj = args.get("targetsFrom");
        if (!(targetsFromObj instanceof Map)) return;
        Map<String, Object> targetsFrom = (Map<String, Object>) targetsFromObj;
        if (!"workflow_variable".equals(targetsFrom.get("type"))) return;
        String variableName = String.valueOf(targetsFrom.get("variableName"));

        JsonNode variableNode = readContextJson(run).path("variables").path(variableName);
        if (variableNode.isMissingNode() || variableNode.isNull()) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': variable '"
                + variableName + "' isn't set yet — this node must run after the node that assigns it");
        }
        String variableType = variableNode.path("variableType").asText(null);
        if (!"asset".equals(variableType)) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': variable '" + variableName
                + "' holds '" + variableType + "', not 'asset' — agent tasks can only target asset-typed variables");
        }

        java.util.Set<String> validAssetTypes = agentToolSpecRegistry.describe(tool).validAssetTypes();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> targets = new ArrayList<>();
        for (JsonNode item : variableNode.path("items")) {
            String assetType = item.path("type").asText(null);
            if (!validAssetTypes.isEmpty() && !validAssetTypes.contains(assetType)) continue;
            String target = formatVariableAssetForTarget(item.path("identifier").asText(null), assetType);
            if (target != null && !target.isBlank() && seen.add(target)) targets.add(target);
        }
        if (targets.isEmpty()) {
            throw new WorkflowValidationException("ACTION_AGENT_TASK node '" + nodeId + "': variable '" + variableName
                + "' has no assets of a type '" + tool + "' supports (" + validAssetTypes + ")");
        }

        args.remove("targetsFrom");
        args.put("targets", targets);
    }

    /** Mirrors {@code TargetResolver#formatAssetForTarget} exactly (SERVICE identifiers are
     *  "ip:port/proto" — scanners want just the "ip:port" prefix) — can't reuse that method
     *  directly since it takes a real {@code Asset} entity and this variable's items are already-
     *  detached JSON summaries ({@code assetSummary()}), not entities. Keep the two in sync if
     *  that reshaping rule ever changes. */
    private String formatVariableAssetForTarget(String identifier, String assetType) {
        if (identifier == null) return null;
        if ("service".equalsIgnoreCase(assetType)) {
            int slash = identifier.lastIndexOf('/');
            return slash > 0 ? identifier.substring(0, slash) : identifier;
        }
        return identifier;
    }


    /**
     * ACTION_INTEGRATION_CALL — generic dispatch to whatever {@link IntegrationActionHandler} is
     * registered for the node's {@code integrationType}, WAITing on the returned ref through the
     * open-ended {@link IntegrationActionRegistry} — see the registry's own doc comment for why
     * this is the one node type designed from the start to grow (new actions, even whole new
     * integration types) without ever touching this method again. This is also where the retired
     * {@code ACTION_SYNC} node type's capabilities ended up (Tenable/Greenbone/Shodan-enrich sync,
     * see {@code com.martecyber.ares.integrations.DataSourceSyncIntegrationActionHandler}) — a
     * node still holding the old type fails {@code WorkflowGraphValidator}'s node-type-supported
     * check before ever reaching here; {@code LegacyScheduleMigrationService} rewrites any
     * already-saved one into this shape at boot. The handler's own returned ref id is stashed
     * under {@code ref_type="INTEGRATION_ACTION"}; the integration type itself is recorded in the
     * step's otherwise-unused {@code input} column, since {@link WorkflowStepPoller} needs it
     * later to know which handler to ask.
     */
    private void executeIntegrationCall(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String integrationType = textOrThrow(config, "integrationType", node.id());
        String action = textOrThrow(config, "action", node.id());
        JsonNode integrationIdNode = config == null ? null : config.get("integrationId");
        if (integrationIdNode == null || !integrationIdNode.isNumber()) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + node.id() + "' requires 'integrationId'");
        }
        Long integrationId = integrationIdNode.asLong();

        Workflow wf = workflowRepo.findById(run.getWorkflowId()).orElseThrow(() -> NotFoundException.of("workflow", run.getWorkflowId()));
        // require() first so a genuinely-unregistered type fails with its own clear, plugin-
        // naming message (see IntegrationActionRegistry#missingHandlerMessage) rather than the
        // scope-mismatch one below.
        var handler = integrationActionRegistry.require(integrationType);
        if (!handler.supportedScopes().contains(wf.getScopeKind())) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + node.id() + "': integration type '"
                + integrationType + "' isn't usable from a " + wf.getScopeKind() + "-scoped workflow");
        }
        // Re-checked at run time, not just at save time — a workflow persists independently of the
        // project it targets, and that project's own type (e.g. bug-hunting) can change after the
        // workflow was saved (see IntegrationActionHandler#isAvailableForScope).
        if (!integrationActionRegistry.isAvailable(integrationType, wf.getScopeKind(), wf.getScopeId())) {
            throw new WorkflowValidationException("ACTION_INTEGRATION_CALL node '" + node.id() + "': integration type '"
                + integrationType + "' isn't available for this " + wf.getScopeKind());
        }

        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        Map<String, Object> params = config.has("paramsTemplate") && !config.get("paramsTemplate").isNull()
            ? MAPPER.convertValue(renderJsonTemplate(config.get("paramsTemplate"), flatContext), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
            : Map.of();

        Long refId = integrationActionRegistry.start(integrationType, action, integrationId, wf.getScopeKind(), wf.getScopeId(), params);

        step.setStatus(WorkflowStepStatus.WAITING);
        step.setRefType("INTEGRATION_ACTION");
        step.setRefId(refId);
        step.setInput(writeJson(Map.of("integrationType", integrationType)));
        step.setOutput(writeJson(Map.of("integrationType", integrationType, "action", action, "refId", refId)));
    }

    /**
     * ACTION_CALL_WORKFLOW — always a topic broadcast: starts every enabled TRIGGER_CALL_TOPIC
     * trigger matching {@code topic}, reachable per {@link #isWithinVerticalScope} (a caller may
     * only reach itself or a scope strictly below it in the platform→organization→project
     * hierarchy — never sideways to another org/project, never upward). One subscriber or many
     * both go through the exact same loop — "call one workflow" is just the one-subscriber case,
     * not a separate code path.
     * <p>
     * Deliberately fire-and-forget, not wait-for-completion: the whole point is a platform-level
     * broadcaster that never needs updating as new subscribers (e.g. a new project's workflow)
     * are added, so waiting on however many subscribers happen to exist today would be the wrong
     * coupling — and {@code WorkflowStepRun} only has room for a single {@code ref_type}/{@code
     * ref_id} anyway, so "wait for all" would need its own schema. One subscriber failing (cycle,
     * depth limit, exception) is logged and skipped, never fails the broadcast step itself.
     * <p>
     * Optional {@code paramsTemplate} (a flat name→value-template map, authored as a list in the
     * editor rather than raw JSON — see {@code NodeConfigPanel.vue}) is rendered via {@link
     * #renderJsonTemplate} and merged into every started child run's trigger context — e.g. a
     * KEV-check broadcaster passing {@code {"cve": "{{trigger.entityId}}"}} to every subscriber.
     */
    /**
     * Saves {@code step} in its own, immediately-committed transaction. Only meaningful when
     * called through {@link #self} (see that field's own doc comment) — a plain {@code this.}
     * call would just join whatever transaction is already open, defeating the point. Used by
     * {@link #executeCallWorkflow} to make the CALL_WORKFLOW node's own step row visible to
     * OTHER, later, genuinely separate {@code REQUIRES_NEW} transactions (each subscriber's
     * {@link #createRun}) before they run — see the call site's doc comment for the full FK
     * violation this fixes.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public WorkflowStepRun commitStepRunImmediately(WorkflowStepRun step) {
        return stepRunRepo.save(step);
    }

    private void executeCallWorkflow(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        String topic = text(config, "topic");
        if (topic == null || topic.isBlank()) {
            throw new WorkflowValidationException("ACTION_CALL_WORKFLOW node '" + node.id() + "' requires 'topic'");
        }
        Workflow callingWf = workflowRepo.findById(run.getWorkflowId()).orElseThrow(() -> NotFoundException.of("workflow", run.getWorkflowId()));
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        Map<String, Object> params = config.has("paramsTemplate") && !config.get("paramsTemplate").isNull()
            ? MAPPER.convertValue(renderJsonTemplate(config.get("paramsTemplate"), flatContext), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
            : Map.of();

        // executeNode() only persists `step` (and assigns its generated id) AFTER this method
        // returns — but start()'s parentStepRunId needs a REAL, COMMITTED id right now: each
        // subscriber's start() call runs createRun() in its OWN REQUIRES_NEW transaction (a
        // genuinely separate physical transaction, suspending this one), and under Postgres READ
        // COMMITTED that transaction cannot see this row until it's actually committed — a plain
        // stepRunRepo.save() here only inserts it into THIS (still-open, REQUIRES_NEW) transaction,
        // so the very first subscriber's createRun() would fail with
        // fk_workflow_run_parent_step. Routing the save through commitStepRunImmediately (its own
        // REQUIRES_NEW, so it commits and returns before this method continues) fixes that — same
        // "commit the row before another transaction needs to see it" pattern as the
        // createRun()/advance() split above. It's still safe for executeNode() to save() this same
        // row again below (and once more after this method returns, with the final status/output):
        // same id, later fields just overwrite the earlier ones.
        WorkflowRunService proxy = self != null ? self : this;
        step = proxy.commitStepRunImmediately(step);

        List<Long> startedRunIds = new ArrayList<>();
        List<WorkflowTrigger> subscribers = triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.CALL_TOPIC);
        for (WorkflowTrigger sub : subscribers) {
            if (!topic.equals(readTopic(sub.getConfig()))) continue;
            Workflow subscriberWf = workflowRepo.findById(sub.getWorkflowId()).orElse(null);
            if (subscriberWf == null || !"active".equals(subscriberWf.getStatus())) continue;
            if (!isWithinVerticalScope(callingWf, subscriberWf)) continue;
            try {
                assertNoCycleOrDepthLimitExceeded(run, sub.getWorkflowId(), node.id());
                WorkflowRun childRun = start(sub.getWorkflowId(), sub.getNodeId(), params,
                    "topic:" + topic, step.getId());
                startedRunIds.add(childRun.getId());
            } catch (Exception e) {
                log.warn("ACTION_CALL_WORKFLOW broadcast '{}': subscriber workflow {} failed to start: {}",
                    topic, sub.getWorkflowId(), e.getMessage());
            }
        }

        step.setStatus(WorkflowStepStatus.COMPLETED);
        step.setOutput(writeJson(Map.of("topic", topic, "subscriberCount", startedRunIds.size(), "childRunIds", startedRunIds)));
    }

    private String readTopic(String configJson) {
        try {
            JsonNode node = MAPPER.readTree(configJson).get("topic");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWithinVerticalScope(Workflow caller, Workflow target) {
        Long targetProjectOrgId = WorkflowScope.PROJECT.equals(target.getScopeKind())
            ? projectRepo.findById(target.getScopeId()).map(Project::getOrganizationId).orElse(null)
            : null;
        return WorkflowScopeHierarchy.isReachable(caller.getScopeKind(), caller.getScopeId(),
            target.getScopeKind(), target.getScopeId(), targetProjectOrgId);
    }

    /**
     * ACTION_WEBHOOK_CALL — POSTs a JSON payload to an admin-configured URL, running
     * synchronously (no async ref/poller needed — same tradeoff {@code GenericWebhookSender}
     * already accepts for outbound notifications: a slow/hung receiver just makes this step, and
     * the run, take longer). Optional HMAC signing (the {@code X-Webhook-Signature} header)
     * mirrors Phase C's inbound webhook exactly, byte-for-byte over the same JSON body that's
     * sent — the secret is authored by the admin via {@link WorkflowService#setOutboundWebhookSecret}
     * to match what the receiver (which may itself be another Ares workflow's TRIGGER_WEBHOOK
     * node) already expects.
     */
    private void executeWebhookCall(WorkflowRun run, WorkflowGraph.Node node, WorkflowStepRun step) {
        JsonNode config = node.data().config();
        Map<String, Object> flatContext = WorkflowContextFlattener.flatten(run.getContext());
        String url = MessagingTemplate.render(textOrThrow(config, "url", node.id()), flatContext);

        String bodyStr = config.has("bodyTemplate") && !config.get("bodyTemplate").isNull()
            ? writeJson(renderJsonTemplate(config.get("bodyTemplate"), flatContext))
            : "";
        byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);

        var req = http.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (config.has("headers") && config.get("headers").isObject()) {
            config.get("headers").fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    req.header(e.getKey(), MessagingTemplate.render(e.getValue().asText(), flatContext));
                }
            });
        }
        Optional<String> secret = workflowService.getOutboundWebhookSecretPlaintext(run.getWorkflowId(), node.id());
        secret.ifPresent(s -> req.header("X-Webhook-Signature", webhookSignatureService.sign(s, bodyBytes)));

        try {
            ResponseEntity<Void> response = req.body(bodyBytes).retrieve().toBodilessEntity();
            step.setStatus(WorkflowStepStatus.COMPLETED);
            step.setOutput(writeJson(Map.of("status", response.getStatusCode().value())));
        } catch (RestClientResponseException e) {
            throw new WorkflowValidationException("ACTION_WEBHOOK_CALL node '" + node.id() + "': HTTP "
                + e.getStatusCode().value() + " from " + url);
        } catch (RestClientException e) {
            throw new WorkflowValidationException("ACTION_WEBHOOK_CALL node '" + node.id() + "': " + e.getMessage());
        }
    }

    /** Walks the ancestor chain (this run → its parent step's run → repeat) via {@code
     *  parent_step_run_id} — necessarily a runtime check, unlike the single-workflow save-time DAG
     *  check, since it spans separate workflow definitions. Rejects if {@code targetWorkflowId}
     *  already appears anywhere in the chain (a cycle, at any depth) or if the chain is already
     *  {@link #maxCallDepth} deep. */
    private void assertNoCycleOrDepthLimitExceeded(WorkflowRun run, Long targetWorkflowId, String nodeId) {
        WorkflowRun current = run;
        int depth = 0;
        while (current != null) {
            if (current.getWorkflowId().equals(targetWorkflowId)) {
                throw new WorkflowValidationException("ACTION_CALL_WORKFLOW node '" + nodeId
                    + "': calling workflow " + targetWorkflowId + " would create a cycle");
            }
            depth++;
            if (depth > maxCallDepth) {
                throw new WorkflowValidationException("ACTION_CALL_WORKFLOW node '" + nodeId
                    + "': call depth exceeds the configured limit (" + maxCallDepth + ")");
            }
            Long parentStepId = current.getParentStepRunId();
            if (parentStepId == null) break;
            WorkflowStepRun parentStep = stepRunRepo.findById(parentStepId).orElse(null);
            current = parentStep == null ? null : runRepo.findById(parentStep.getWorkflowRunId()).orElse(null);
        }
    }

    private JsonNode renderJsonTemplate(JsonNode node, Map<String, Object> vars) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) return TextNode.valueOf(MessagingTemplate.render(node.asText(), vars));
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), renderJsonTemplate(e.getValue(), vars)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> out.add(renderJsonTemplate(child, vars)));
            return out;
        }
        return node;
    }

    private String renderTemplateField(JsonNode config, String field, Map<String, Object> vars, String fallback) {
        if (config == null || !config.has(field) || !config.get(field).isTextual()) return fallback;
        return MessagingTemplate.render(config.get(field).asText(), vars);
    }

    private String text(JsonNode config, String field) {
        return config != null && config.has(field) && config.get(field).isTextual() ? config.get(field).asText() : null;
    }

    private String textOrThrow(JsonNode config, String field, String nodeId) {
        if (config == null || !config.has(field) || !config.get(field).isTextual()) {
            throw new WorkflowValidationException("Node '" + nodeId + "' requires '" + field + "'");
        }
        return config.get(field).asText();
    }

    private boolean readConditionResult(WorkflowStepRun step) {
        try {
            return MAPPER.readTree(step.getOutput()).path("result").asBoolean(false);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt CONDITION step output for step " + step.getId(), e);
        }
    }

    private Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WorkflowGraph parseGraph(String json) {
        try {
            return MAPPER.readValue(json, WorkflowGraph.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt workflow graph JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow value", e);
        }
    }
}
