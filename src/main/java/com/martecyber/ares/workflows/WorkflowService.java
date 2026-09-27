package com.martecyber.ares.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PlatformSettingsService;
import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.webhooks.OutboundWebhookSecret;
import com.martecyber.ares.webhooks.OutboundWebhookSecretRepository;
import com.martecyber.ares.webhooks.WebhookEndpoint;
import com.martecyber.ares.webhooks.WebhookEndpointRepository;
import com.martecyber.ares.webhooks.WebhookSignatureService;
import com.martecyber.ares.workflows.graph.WorkflowGraph;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CRUD for {@link Workflow}, plus keeping {@link WorkflowTrigger} rows in sync with whatever
 *  TRIGGER_* nodes the graph currently has — full replace on every save (trigger rows carry no
 *  independent state besides {@code nextRunAt}, which is recomputed anyway), not a diff. Also
 *  keeps {@code webhook_endpoint} rows in sync with TRIGGER_WEBHOOK nodes specifically — those
 *  carry real independent state (the token/secret an external caller has already been configured
 *  with) that must survive a trigger resync untouched, see {@link #provisionWebhookIfAbsent}. */
@Service
public class WorkflowService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowRepository workflowRepo;
    private final WorkflowTriggerRepository triggerRepo;
    private final WorkflowGraphValidator validator;
    private final WebhookEndpointRepository webhookRepo;
    private final WebhookSignatureService signatureService;
    private final CredentialEncryptionService credentialEncryption;
    private final OutboundWebhookSecretRepository outboundSecretRepo;
    private final ProjectRepository projectRepo;
    private final PlatformSettingsService platformSettings;
    private final WorkflowRunRepository runRepo;

    public WorkflowService(WorkflowRepository workflowRepo, WorkflowTriggerRepository triggerRepo,
                            WorkflowGraphValidator validator, WebhookEndpointRepository webhookRepo,
                            WebhookSignatureService signatureService, CredentialEncryptionService credentialEncryption,
                            OutboundWebhookSecretRepository outboundSecretRepo, ProjectRepository projectRepo,
                            PlatformSettingsService platformSettings, WorkflowRunRepository runRepo) {
        this.workflowRepo = workflowRepo;
        this.triggerRepo = triggerRepo;
        this.validator = validator;
        this.webhookRepo = webhookRepo;
        this.signatureService = signatureService;
        this.credentialEncryption = credentialEncryption;
        this.outboundSecretRepo = outboundSecretRepo;
        this.projectRepo = projectRepo;
        this.platformSettings = platformSettings;
        this.runRepo = runRepo;
    }

    public List<Workflow> listByScope(String scopeKind, Long scopeId) {
        return workflowRepo.findByScopeKindAndScopeId(scopeKind, scopeId);
    }

    public Workflow get(Long id) {
        return workflowRepo.findById(id).orElseThrow(() -> NotFoundException.of("workflow", id));
    }

    public List<WorkflowTrigger> triggersFor(Long workflowId) {
        return triggerRepo.findByWorkflowId(workflowId);
    }

    @Transactional
    public WorkflowSaveResult create(String scopeKind, Long scopeId, String name, String description,
                            Map<String, Object> graphDefinition, Long createdBy) {
        String graphJson = writeJson(graphDefinition);
        // A freshly created workflow always starts in draft — never requireComplete here, same
        // reasoning as update()'s resolvedStatus check below.
        WorkflowValidationResult validated = validator.validate(scopeKind, scopeId, graphJson, false);
        WorkflowGraph graph = validated.graph();

        OffsetDateTime now = OffsetDateTime.now();
        Workflow wf = new Workflow();
        wf.setScopeKind(scopeKind);
        wf.setScopeId(scopeId);
        wf.setName(name);
        wf.setDescription(description);
        wf.setStatus("draft");
        wf.setGraphDefinition(graphJson);
        wf.setVersion(1);
        wf.setCreatedBy(createdBy);
        wf.setCreatedAt(now);
        wf.setUpdatedAt(now);
        wf = workflowRepo.save(wf);

        syncTriggers(wf, graph);
        return new WorkflowSaveResult(wf, validated.warnings());
    }

    @Transactional
    public WorkflowSaveResult update(Long id, String name, String description, String status, Map<String, Object> graphDefinition) {
        Workflow wf = get(id);
        if (wf.isLocked()) {
            throw new WorkflowValidationException(
                "This workflow is managed automatically (" + wf.getManagedBy() + ") — manage it from its origin page instead.");
        }
        if (name != null) wf.setName(name);
        if (description != null) wf.setDescription(description);
        if (status != null) wf.setStatus(status);
        wf.setUpdatedAt(OffsetDateTime.now());

        // Scope-bound resource ids (pool, integration) are only required once the workflow is
        // actually going to run — draft/disabled workflows can be saved mid-configuration
        // (including one just instantiated from a template, still missing those ids).
        boolean requireComplete = "active".equals(wf.getStatus());

        if (graphDefinition != null) {
            String graphJson = writeJson(graphDefinition);
            WorkflowValidationResult validated = validator.validate(wf.getScopeKind(), wf.getScopeId(), graphJson, requireComplete);
            wf.setGraphDefinition(graphJson);
            wf.setVersion(wf.getVersion() + 1);
            wf = workflowRepo.save(wf);
            syncTriggers(wf, validated.graph());
            return new WorkflowSaveResult(wf, validated.warnings());
        }
        // A bare status-only update (no graph in this request) must still satisfy requireComplete
        // when the resolved status is 'active' — otherwise an incomplete draft (e.g. one just
        // instantiated from a template, still missing poolId/integrationId) could be flipped
        // straight to active by a status-only PATCH that never re-touches the graph at all.
        List<WorkflowValidationWarning> warnings = List.of();
        if (requireComplete) {
            warnings = validator.validate(wf.getScopeKind(), wf.getScopeId(), wf.getGraphDefinition(), true).warnings();
        }
        return new WorkflowSaveResult(workflowRepo.save(wf), warnings);
    }

    /** Package-private — only {@link ManagedWorkflowService} calls this. Upserts by {@code
     *  (managedBy, scopeKind, scopeId)} instead of always inserting, so re-saving the same schedule
     *  (e.g. changing a cron expression) updates the existing managed workflow in place rather than
     *  accumulating duplicates. Unlike {@link #create}, starts life {@code active} and validates
     *  with {@code requireComplete=true} — a managed workflow only ever gets created once its
     *  origin page already has a real integration/target id, so it should always be immediately
     *  runnable, never left in a half-configured draft state the way a hand-authored one can be. */
    @Transactional
    Workflow createOrUpdateManaged(String managedBy, String scopeKind, Long scopeId, String name,
                                    String description, Map<String, Object> graphDefinition) {
        String graphJson = writeJson(graphDefinition);
        WorkflowGraph graph = validator.validate(scopeKind, scopeId, graphJson, true).graph();
        OffsetDateTime now = OffsetDateTime.now();

        Workflow wf = workflowRepo.findByManagedByAndScopeKindAndScopeId(managedBy, scopeKind, scopeId).orElseGet(Workflow::new);
        boolean isNew = wf.getId() == null;
        wf.setScopeKind(scopeKind);
        wf.setScopeId(scopeId);
        wf.setName(name);
        wf.setDescription(description);
        wf.setStatus("active");
        wf.setGraphDefinition(graphJson);
        wf.setVersion(isNew ? 1 : wf.getVersion() + 1);
        wf.setLocked(true);
        wf.setManagedBy(managedBy);
        if (isNew) wf.setCreatedAt(now);
        wf.setUpdatedAt(now);
        wf = workflowRepo.save(wf);

        syncTriggers(wf, graph);
        return wf;
    }

    /** Package-private — only {@link ManagedWorkflowService} calls this. Unlike {@link
     *  #createOrUpdateManaged}, always inserts a new locked workflow rather than upserting by
     *  {@code (managedBy, scopeKind, scopeId)} — for systems where {@code managedBy} is a filter
     *  tag shared by several concurrent schedules (e.g. KB sync allows multiple cron schedules for
     *  the same sync type), not a uniqueness key. */
    @Transactional
    Workflow createManaged(String managedBy, String scopeKind, Long scopeId, String name,
                            String description, Map<String, Object> graphDefinition) {
        String graphJson = writeJson(graphDefinition);
        WorkflowGraph graph = validator.validate(scopeKind, scopeId, graphJson, true).graph();
        OffsetDateTime now = OffsetDateTime.now();

        Workflow wf = new Workflow();
        wf.setScopeKind(scopeKind);
        wf.setScopeId(scopeId);
        wf.setName(name);
        wf.setDescription(description);
        wf.setStatus("active");
        wf.setGraphDefinition(graphJson);
        wf.setVersion(1);
        wf.setLocked(true);
        wf.setManagedBy(managedBy);
        wf.setCreatedAt(now);
        wf.setUpdatedAt(now);
        wf = workflowRepo.save(wf);

        syncTriggers(wf, graph);
        return wf;
    }

    /** Package-private — only {@link ManagedWorkflowService} calls this. Updates an existing
     *  managed workflow's graph in place (same id, name/description/scope untouched, bumps
     *  version) — for systems where an existing schedule can be edited (e.g. changing just the
     *  cron expression) rather than only created/deleted; bypasses {@link #update}'s lock guard,
     *  which exists specifically to stop this same edit happening through the ordinary Workflows
     *  UI/API. */
    @Transactional
    Workflow updateManagedGraph(Long id, Map<String, Object> graphDefinition) {
        Workflow wf = get(id);
        String graphJson = writeJson(graphDefinition);
        WorkflowGraph graph = validator.validate(wf.getScopeKind(), wf.getScopeId(), graphJson, true).graph();
        wf.setGraphDefinition(graphJson);
        wf.setVersion(wf.getVersion() + 1);
        wf.setUpdatedAt(OffsetDateTime.now());
        wf = workflowRepo.save(wf);
        syncTriggers(wf, graph);
        return wf;
    }

    /** Package-private — only {@link ManagedWorkflowService} calls this. {@link WorkflowTrigger
     *  #enabled} (not {@link Workflow#status}) is what actually gates {@link WorkflowCronPoller},
     *  so toggling status alone would leave a "paused" managed workflow still firing — both are
     *  updated together here. */
    @Transactional
    void setManagedEnabled(Long id, boolean enabled) {
        Workflow wf = get(id);
        wf.setStatus(enabled ? "active" : "disabled");
        wf.setUpdatedAt(OffsetDateTime.now());
        workflowRepo.save(wf);

        for (WorkflowTrigger t : triggerRepo.findByWorkflowId(id)) {
            t.setEnabled(enabled);
            if (enabled && WorkflowTriggerType.CRON.equals(t.getTriggerType())) {
                String cronExpr = readCronExpression(t.getConfig());
                t.setNextRunAt(cronExpr == null ? null : WorkflowCronPoller.computeNext(cronExpr, ZoneId.of(platformSettings.getTimezone())));
            }
            triggerRepo.save(t);
        }
    }

    /** Package-private — only {@link ManagedWorkflowService} calls this; bypasses {@link #delete}'s
     *  lock guard, which exists specifically to stop this same deletion happening through the
     *  ordinary Workflows UI/API. */
    @Transactional
    void deleteManaged(Long id) {
        assertNoActiveRuns(id);
        workflowRepo.deleteById(id);
    }

    @Transactional
    public void delete(Long id) {
        Workflow wf = get(id);
        if (wf.isLocked()) {
            throw new WorkflowValidationException(
                "This workflow is managed automatically (" + wf.getManagedBy() + ") — manage it from its origin page instead.");
        }
        assertNoActiveRuns(id);
        // workflow_trigger/workflow_run both ON DELETE CASCADE from workflow (V146) — one delete suffices.
        workflowRepo.deleteById(id);
    }

    /** {@code workflow_run}/{@code workflow_step_run} cascade-delete from {@code workflow} at the
     *  DB level (V146), which Hibernate/JPA has no visibility into. {@link WorkflowRunService#advance}
     *  and {@code executeNode} run as separate {@code REQUIRES_NEW} transactions (read the run, then
     *  later insert a new step row), so a delete landing in that window previously surfaced as a raw
     *  {@code workflow_step_run_workflow_run_id_fkey} violation instead of a clear error — block it
     *  here instead. */
    private void assertNoActiveRuns(Long id) {
        if (runRepo.existsByWorkflowIdAndStatusIn(id, List.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING))) {
            throw new WorkflowValidationException(
                "This workflow has a run still in progress — wait for it to finish (or cancel it) before deleting the workflow.");
        }
    }

    private void syncTriggers(Workflow wf, WorkflowGraph graph) {
        triggerRepo.deleteByWorkflowId(wf.getId());
        Set<String> currentWebhookNodeIds = new HashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (!WorkflowNodeType.isTrigger(node.type())) continue;
            String configJson = node.data() != null && node.data().config() != null
                ? node.data().config().toString() : "{}";

            WorkflowTrigger t = new WorkflowTrigger();
            t.setWorkflowId(wf.getId());
            t.setNodeId(node.id());
            t.setTriggerType(triggerTypeFor(node.type()));
            t.setConfig(configJson);
            t.setEnabled(true);
            t.setCreatedAt(OffsetDateTime.now());
            if (WorkflowTriggerType.CRON.equals(t.getTriggerType())) {
                String cronExpr = readCronExpression(configJson);
                t.setNextRunAt(cronExpr == null ? null : WorkflowCronPoller.computeNext(cronExpr, ZoneId.of(platformSettings.getTimezone())));
            }
            triggerRepo.save(t);

            if (WorkflowTriggerType.WEBHOOK.equals(t.getTriggerType())) {
                currentWebhookNodeIds.add(node.id());
                provisionWebhookIfAbsent(wf.getId(), node.id());
            }
        }
        cleanupOrphanedWebhooks(wf.getId(), currentWebhookNodeIds);

        Set<String> currentWebhookCallNodeIds = new HashSet<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            if (WorkflowNodeType.ACTION_WEBHOOK_CALL.equals(node.type())) {
                currentWebhookCallNodeIds.add(node.id());
            }
        }
        cleanupOrphanedOutboundSecrets(wf.getId(), currentWebhookCallNodeIds);
    }

    /** Creates a {@code webhook_endpoint} row (fresh random token + HMAC secret) the first time a
     *  TRIGGER_WEBHOOK node with this {@code nodeId} is saved; a no-op on every later save of the
     *  same node, so the URL an external caller was configured with never rotates underneath it. */
    private void provisionWebhookIfAbsent(Long workflowId, String nodeId) {
        if (webhookRepo.findByWorkflowIdAndNodeId(workflowId, nodeId).isPresent()) return;
        var encrypted = credentialEncryption.encrypt(signatureService.generateSecret());
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setToken(signatureService.generateToken());
        endpoint.setWorkflowId(workflowId);
        endpoint.setNodeId(nodeId);
        endpoint.setSecretCiphertext(encrypted.ciphertext());
        endpoint.setSecretIv(encrypted.iv());
        endpoint.setEnabled(true);
        endpoint.setCreatedAt(OffsetDateTime.now());
        webhookRepo.save(endpoint);
    }

    /** A TRIGGER_WEBHOOK node removed from the graph (deleted, or the whole workflow deleted —
     *  though that case is handled by the FK's ON DELETE CASCADE instead) should stop accepting
     *  requests, not linger forever as a dangling authenticated endpoint. */
    private void cleanupOrphanedWebhooks(Long workflowId, Set<String> currentNodeIds) {
        for (WebhookEndpoint endpoint : webhookRepo.findByWorkflowId(workflowId)) {
            if (!currentNodeIds.contains(endpoint.getNodeId())) {
                webhookRepo.delete(endpoint);
            }
        }
    }

    /** Decrypted webhook info for the editor UI to display (URL + secret) — only ever called
     *  behind the same auth checks {@link WorkflowController} already applies to the owning
     *  workflow, so there's no separate access-control concern here. */
    public record WebhookInfo(String token, String secret, boolean enabled, long requestCount, OffsetDateTime lastTriggeredAt) {}

    public WebhookInfo getWebhookInfo(Long workflowId, String nodeId) {
        WebhookEndpoint endpoint = webhookRepo.findByWorkflowIdAndNodeId(workflowId, nodeId)
            .orElseThrow(() -> NotFoundException.of("webhook endpoint for node", nodeId));
        String secret = credentialEncryption.decrypt(endpoint.getSecretCiphertext(), endpoint.getSecretIv());
        return new WebhookInfo(endpoint.getToken(), secret, endpoint.isEnabled(), endpoint.getRequestCount(), endpoint.getLastTriggeredAt());
    }

    /** Rotates a webhook's secret (e.g. after a suspected leak) — the token/URL stays the same,
     *  only the HMAC key changes, so the external caller needs re-configuring but not re-pointing. */
    @Transactional
    public WebhookInfo regenerateWebhookSecret(Long workflowId, String nodeId) {
        WebhookEndpoint endpoint = webhookRepo.findByWorkflowIdAndNodeId(workflowId, nodeId)
            .orElseThrow(() -> NotFoundException.of("webhook endpoint for node", nodeId));
        var encrypted = credentialEncryption.encrypt(signatureService.generateSecret());
        endpoint.setSecretCiphertext(encrypted.ciphertext());
        endpoint.setSecretIv(encrypted.iv());
        webhookRepo.save(endpoint);
        return getWebhookInfo(workflowId, nodeId);
    }

    /** An ACTION_WEBHOOK_CALL node removed from the graph should not leave its signing secret
     *  behind indefinitely, mirroring {@link #cleanupOrphanedWebhooks}. */
    private void cleanupOrphanedOutboundSecrets(Long workflowId, Set<String> currentNodeIds) {
        for (OutboundWebhookSecret secret : outboundSecretRepo.findByWorkflowId(workflowId)) {
            if (!currentNodeIds.contains(secret.getNodeId())) {
                outboundSecretRepo.delete(secret);
            }
        }
    }

    /** Write-only: the admin authors this secret to match what the external receiver already
     *  expects, so unlike {@link #getWebhookInfo} there is no matching plaintext getter — only
     *  {@link #hasOutboundWebhookSecret} to report whether one is configured. */
    @Transactional
    public void setOutboundWebhookSecret(Long workflowId, String nodeId, String secret) {
        var encrypted = credentialEncryption.encrypt(secret);
        OutboundWebhookSecret entity = outboundSecretRepo.findByWorkflowIdAndNodeId(workflowId, nodeId)
            .orElseGet(() -> {
                OutboundWebhookSecret s = new OutboundWebhookSecret();
                s.setWorkflowId(workflowId);
                s.setNodeId(nodeId);
                s.setCreatedAt(OffsetDateTime.now());
                return s;
            });
        entity.setSecretCiphertext(encrypted.ciphertext());
        entity.setSecretIv(encrypted.iv());
        outboundSecretRepo.save(entity);
    }

    public boolean hasOutboundWebhookSecret(Long workflowId, String nodeId) {
        return outboundSecretRepo.findByWorkflowIdAndNodeId(workflowId, nodeId).isPresent();
    }

    /** Decrypted secret for {@link WorkflowRunService#executeWebhookCall} to sign a delivery with —
     *  intentionally not exposed through {@link WorkflowController}; see {@link #setOutboundWebhookSecret}. */
    java.util.Optional<String> getOutboundWebhookSecretPlaintext(Long workflowId, String nodeId) {
        return outboundSecretRepo.findByWorkflowIdAndNodeId(workflowId, nodeId)
            .map(s -> credentialEncryption.decrypt(s.getSecretCiphertext(), s.getSecretIv()));
    }

    @Transactional
    public void clearOutboundWebhookSecret(Long workflowId, String nodeId) {
        outboundSecretRepo.deleteByWorkflowIdAndNodeId(workflowId, nodeId);
    }

    /** Distinct topics from every enabled TRIGGER_CALL_TOPIC trigger reachable (per {@link
     *  WorkflowScopeHierarchy}, the same rule {@link WorkflowRunService} enforces at broadcast
     *  time) from the given scope — powers ACTION_CALL_WORKFLOW's topic-field autocomplete in the
     *  editor. Deliberately scope-based rather than workflow-id-based so it works for a brand-new,
     *  not-yet-saved workflow too (the editor always knows its own scope before it has an id). */
    public List<String> reachableTopics(String scopeKind, Long scopeId) {
        Set<String> topics = new java.util.TreeSet<>();
        for (WorkflowTrigger trigger : triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.CALL_TOPIC)) {
            Workflow wf = workflowRepo.findById(trigger.getWorkflowId()).orElse(null);
            if (wf == null || !"active".equals(wf.getStatus())) continue;
            Long targetProjectOrgId = WorkflowScope.PROJECT.equals(wf.getScopeKind())
                ? projectRepo.findById(wf.getScopeId()).map(Project::getOrganizationId).orElse(null)
                : null;
            if (!WorkflowScopeHierarchy.isReachable(scopeKind, scopeId, wf.getScopeKind(), wf.getScopeId(), targetProjectOrgId)) continue;
            String topic = readTopicField(trigger.getConfig());
            if (topic != null && !topic.isBlank()) topics.add(topic);
        }
        return List.copyOf(topics);
    }

    private String readTopicField(String configJson) {
        try {
            var node = MAPPER.readTree(configJson).get("topic");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String triggerTypeFor(String nodeType) {
        return switch (nodeType) {
            case WorkflowNodeType.TRIGGER_MANUAL -> WorkflowTriggerType.MANUAL;
            case WorkflowNodeType.TRIGGER_CRON -> WorkflowTriggerType.CRON;
            case WorkflowNodeType.TRIGGER_WEBHOOK -> WorkflowTriggerType.WEBHOOK;
            case WorkflowNodeType.TRIGGER_EVENT -> WorkflowTriggerType.EVENT;
            case WorkflowNodeType.TRIGGER_CALL_TOPIC -> WorkflowTriggerType.CALL_TOPIC;
            default -> throw new IllegalStateException("Not a trigger node type: " + nodeType);
        };
    }

    private String readCronExpression(String configJson) {
        try {
            var node = MAPPER.readTree(configJson).get("cronExpression");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of("nodes", List.of(), "edges", List.of()) : value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize workflow graph", e);
        }
    }
}
