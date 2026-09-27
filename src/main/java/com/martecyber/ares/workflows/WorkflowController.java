package com.martecyber.ares.workflows;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.workflows.dto.WorkflowDtos.*;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Scope-aware CRUD for Workflows (platform/org/project), plus manual-run and run-history
 * endpoints shared across all three scopes (workflow id alone is unambiguous once access is
 * checked) — same routing/permission shape as {@code MessagingBindingController}/{@code
 * PlatformBindingController}.
 */
@RestController
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowRunService runService;
    private final WorkflowRunRepository runRepo;
    private final WorkflowStepRunRepository stepRunRepo;
    private final OrgScopeService orgScope;
    private final IntegrationActionRegistry integrationActionRegistry;

    public WorkflowController(WorkflowService workflowService, WorkflowRunService runService,
                               WorkflowRunRepository runRepo, WorkflowStepRunRepository stepRunRepo,
                               OrgScopeService orgScope, IntegrationActionRegistry integrationActionRegistry) {
        this.workflowService = workflowService;
        this.runService = runService;
        this.runRepo = runRepo;
        this.stepRunRepo = stepRunRepo;
        this.orgScope = orgScope;
        this.integrationActionRegistry = integrationActionRegistry;
    }

    // ── Platform-scoped ────────────────────────────────────────────

    @GetMapping("/api/v1/admin/workflows")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public List<WorkflowDto> listPlatform() {
        return workflowService.listByScope(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID).stream()
            .map(this::dto).toList();
    }

    @PostMapping("/api/v1/admin/workflows")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<WorkflowDto> createPlatform(@RequestBody CreateWorkflowRequest req, Authentication auth) {
        WorkflowSaveResult result = workflowService.create(WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID,
            requireName(req), req.description(), req.graphDefinition(), currentUserId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(result));
    }

    // ── Org-scoped ─────────────────────────────────────────────────

    @GetMapping("/api/v1/organizations/{orgId}/workflows")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<WorkflowDto> listForOrg(@PathVariable Long orgId, Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        return workflowService.listByScope(WorkflowScope.ORGANIZATION, orgId).stream().map(this::dto).toList();
    }

    @PostMapping("/api/v1/organizations/{orgId}/workflows")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<WorkflowDto> createForOrg(@PathVariable Long orgId, @RequestBody CreateWorkflowRequest req,
                                                     Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        WorkflowSaveResult result = workflowService.create(WorkflowScope.ORGANIZATION, orgId,
            requireName(req), req.description(), req.graphDefinition(), currentUserId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(result));
    }

    // ── Project-scoped ─────────────────────────────────────────────

    @GetMapping("/api/v1/projects/{engId}/workflows")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<WorkflowDto> listForProject(@PathVariable Long engId, Authentication auth) {
        orgScope.assertProjectAccess(auth, engId);
        return workflowService.listByScope(WorkflowScope.PROJECT, engId).stream().map(this::dto).toList();
    }

    @PostMapping("/api/v1/projects/{engId}/workflows")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<WorkflowDto> createForProject(@PathVariable Long engId, @RequestBody CreateWorkflowRequest req,
                                                         Authentication auth) {
        orgScope.assertProjectAccess(auth, engId);
        WorkflowSaveResult result = workflowService.create(WorkflowScope.PROJECT, engId,
            requireName(req), req.description(), req.graphDefinition(), currentUserId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(result));
    }

    /** Distinct topics ACTION_CALL_WORKFLOW's editor can autocomplete for a workflow being edited
     *  in this scope — deliberately takes scopeKind/scopeId rather than a workflow id, so it works
     *  before the workflow being edited has ever been saved. */
    @GetMapping("/api/v1/workflows/topics")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<String> reachableTopics(@RequestParam String scopeKind, @RequestParam Long scopeId, Authentication auth) {
        switch (scopeKind) {
            case WorkflowScope.PLATFORM -> {
                if (!orgScope.isPlatformAdmin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform scope requires MSSP_ADMIN");
            }
            case WorkflowScope.ORGANIZATION -> orgScope.assertOrgAccess(auth, scopeId);
            case WorkflowScope.PROJECT -> orgScope.assertProjectAccess(auth, scopeId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scopeKind '" + scopeKind + "'");
        }
        return workflowService.reachableTopics(scopeKind, scopeId);
    }

    /** ACTION_INTEGRATION_CALL's own registry-backed catalog — one entry per currently registered
     *  {@code IntegrationActionHandler} type whose {@code supportedScopes()} includes this scope
     *  (Caido is project-only, KB sync platform-only, Shodan org-only — see each handler), each
     *  carrying the actions it supports and the instances usable from this scope. */
    @GetMapping("/api/v1/workflows/integration-actions")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<IntegrationActionCatalogEntry> integrationActionCatalog(
            @RequestParam String scopeKind, @RequestParam Long scopeId, Authentication auth) {
        switch (scopeKind) {
            case WorkflowScope.PLATFORM -> {
                if (!orgScope.isPlatformAdmin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform scope requires MSSP_ADMIN");
            }
            case WorkflowScope.ORGANIZATION -> orgScope.assertOrgAccess(auth, scopeId);
            case WorkflowScope.PROJECT -> orgScope.assertProjectAccess(auth, scopeId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scopeKind '" + scopeKind + "'");
        }
        return integrationActionRegistry.all().stream()
            .filter(h -> h.supportedScopes().contains(scopeKind) && h.isAvailableForScope(scopeKind, scopeId))
            .map(h -> new IntegrationActionCatalogEntry(h.integrationType(), h.integrationTypeLabel(),
                h.describeActions(), h.listInstances(scopeKind, scopeId)))
            .toList();
    }

    // ── Shared (id alone is unambiguous once access is checked) ────

    @GetMapping("/api/v1/workflows/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowDto get(@PathVariable Long id, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        return dto(wf);
    }

    @PatchMapping("/api/v1/workflows/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowDto update(@PathVariable Long id, @RequestBody UpdateWorkflowRequest req, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        return dto(workflowService.update(id, req.name(), req.description(), req.status(), req.graphDefinition()));
    }

    @DeleteMapping("/api/v1/workflows/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Fires a MANUAL trigger now. {@code body} is optional extra trigger context (e.g.
     *  {@code entityType}/{@code entityId} to bind a CONDITION node against a real entity while
     *  testing a workflow before it has a real EVENT trigger wired). */
    @PostMapping("/api/v1/workflows/{id}/triggers/{triggerId}/run")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public RunDto runManualTrigger(@PathVariable Long id, @PathVariable Long triggerId,
                                    @RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        WorkflowTrigger trigger = workflowService.triggersFor(id).stream()
            .filter(t -> t.getId().equals(triggerId)).findFirst()
            .orElseThrow(() -> NotFoundException.of("workflow trigger", triggerId));
        if (!WorkflowTriggerType.MANUAL.equals(trigger.getTriggerType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trigger '" + triggerId + "' is not a manual trigger");
        }
        WorkflowRun run = runService.start(id, trigger.getNodeId(), body, auth.getName(), null);
        return RunDto.from(run);
    }

    /** Decrypted webhook URL/secret for a TRIGGER_WEBHOOK node — the editor UI shows this so the
     *  admin can configure the external caller (GitHub, a monitoring system, ...). */
    @GetMapping("/api/v1/workflows/{id}/webhook/{nodeId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowService.WebhookInfo getWebhookInfo(@PathVariable Long id, @PathVariable String nodeId, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        return workflowService.getWebhookInfo(id, nodeId);
    }

    @PostMapping("/api/v1/workflows/{id}/webhook/{nodeId}/regenerate-secret")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowService.WebhookInfo regenerateWebhookSecret(@PathVariable Long id, @PathVariable String nodeId, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        return workflowService.regenerateWebhookSecret(id, nodeId);
    }

    /** Whether an ACTION_WEBHOOK_CALL node has a signing secret configured — never returns the
     *  plaintext, since it's write-only (the admin authored it to match an external system). */
    @GetMapping("/api/v1/workflows/{id}/outbound-secret/{nodeId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Boolean> getOutboundWebhookSecretStatus(@PathVariable Long id, @PathVariable String nodeId, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        return Map.of("configured", workflowService.hasOutboundWebhookSecret(id, nodeId));
    }

    @PutMapping("/api/v1/workflows/{id}/outbound-secret/{nodeId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Boolean> setOutboundWebhookSecret(@PathVariable Long id, @PathVariable String nodeId,
                                                           @RequestBody Map<String, String> body, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        String secret = body.get("secret");
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'secret' is required");
        }
        workflowService.setOutboundWebhookSecret(id, nodeId, secret);
        return Map.of("configured", true);
    }

    @DeleteMapping("/api/v1/workflows/{id}/outbound-secret/{nodeId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> clearOutboundWebhookSecret(@PathVariable Long id, @PathVariable String nodeId, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        workflowService.clearOutboundWebhookSecret(id, nodeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/workflows/{id}/runs")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<RunDto> listRuns(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "startedAt"));
        return PagedResponse.of(runRepo.findByWorkflowIdOrderByStartedAtDesc(id, pageable), RunDto::from);
    }

    @GetMapping("/api/v1/workflows/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public RunDetailDto getRun(@PathVariable Long id, @PathVariable Long runId, Authentication auth) {
        Workflow wf = workflowService.get(id);
        assertWorkflowAccess(auth, wf);
        WorkflowRun run = runRepo.findById(runId).orElseThrow(() -> NotFoundException.of("workflow run", runId));
        if (!run.getWorkflowId().equals(id)) {
            throw NotFoundException.of("workflow run", runId);
        }
        return RunDetailDto.from(run, stepRunRepo.findByWorkflowRunId(runId));
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** The shared endpoints below are method-gated to {@code hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')}
     *  (needed for org/project scope, where MSSP_OPERATOR is legitimate) — platform-scoped
     *  workflows need their own narrower check here since creation is MSSP_ADMIN-only
     *  (`/api/v1/admin/workflows`); without this an MSSP_OPERATOR could edit/delete/run a
     *  platform workflow it was never allowed to create. */
    private void assertWorkflowAccess(Authentication auth, Workflow wf) {
        switch (wf.getScopeKind()) {
            case WorkflowScope.PLATFORM -> {
                if (!orgScope.isPlatformAdmin(auth)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                        "Platform-scoped workflows require MSSP_ADMIN");
                }
            }
            case WorkflowScope.ORGANIZATION -> orgScope.assertOrgAccess(auth, wf.getScopeId());
            case WorkflowScope.PROJECT -> orgScope.assertProjectAccess(auth, wf.getScopeId());
            default -> throw new IllegalStateException("Unknown workflow scopeKind: " + wf.getScopeKind());
        }
    }

    private String requireName(CreateWorkflowRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return req.name();
    }

    private Long currentUserId(Authentication auth) {
        try {
            return Long.parseLong(auth.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private WorkflowDto dto(Workflow wf) {
        return WorkflowDto.from(wf, workflowService.triggersFor(wf.getId()));
    }

    private WorkflowDto dto(WorkflowSaveResult result) {
        return WorkflowDto.from(result.workflow(), workflowService.triggersFor(result.workflow().getId()), result.warnings());
    }
}
