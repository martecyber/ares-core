package com.martecyber.ares.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingPresentationService;
import com.martecyber.ares.findings.templates.FindingTemplate;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflows implementation plan, Phase D — TRIGGER_EVENT: a concrete, named thing happened. Same
 * synchronous, try/catch-log-swallow shape as {@code MessagingDispatcher.onDetectionCreated}
 * (that class's own doc comment explains why: a workflow/notification failure must never poison
 * the caller's save transaction) — deliberately not the same class, since Workflows' own dispatch
 * target (a {@code WorkflowTrigger} row) and matching rule (a single {@code eventCode} string +
 * owning workflow's scope/status) are structurally different from a {@code MessagingEventBinding}'s.
 * <p>
 * Deliberately a flat catalog of concrete event codes (e.g. {@code "detection.created"}, {@code
 * "cve.kev_added"}) — not a generic {@code entityType}+{@code event} pair. The user explicitly
 * asked for this after using the generic version: a single dropdown of specific, nameable things
 * ("CVE added to KEV catalog", "CVE got a new PoC") rather than only "created/updated/deleted" per
 * entity, with each code free to define its own payload shape rather than sharing one generic
 * entity-snapshot shape. Every dispatch still carries a small, curated field snapshot of the
 * triggering entity (plus whatever extra fields that specific event needs — e.g. {@code
 * cve.kev_added} adds {@code catalog}/{@code dateAdded} on top of the normal CVE fields), keyed
 * under {@code trigger.<type>.*} where {@code <type>} is the eventCode's own prefix up to the
 * first {@code '.'} (e.g. {@code detection.created} → {@code trigger.detection.*}, {@code
 * cve.kev_added} → {@code trigger.cve.*}) — not a fixed {@code trigger.entity.*} — so a downstream
 * node's {@code {{...}}} template/context-var picker can tell which entity type it's dealing with
 * from the key itself, and multiple TRIGGER_EVENT-fed variables of different types never collide
 * on the same generic name. This is a clean-break redesign of the earlier {@code entityType}+
 * {@code event} shape (see git history) — no backward compatibility kept, since this feature has
 * no production data yet and was still being actively designed with the user when this landed.
 * <p>
 * "Condition became true" triggers that need to diff state *across* a sync batch rather than react
 * to one already-known transition a specific method already computed (e.g. a KB-wide "some CVE's
 * CVSS score changed by ≥2 points") are still out of scope here — that's the real Phase G, not this.
 */
@Component
public class WorkflowEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowTriggerRepository triggerRepo;
    private final WorkflowRepository workflowRepo;
    private final WorkflowRunService runService;
    private final ProjectRepository projectRepo;
    private final FindingPresentationService findingPresentationService;

    // WorkflowRunService depends on Asset/Finding/Detection services (ASSIGN_VARIABLE query
    // mode) — those services are exactly the ones calling into this dispatcher on entity
    // creation, so an eager injection here would be a real circular-dependency cycle at context
    // startup. @Lazy breaks it: a proxy is injected now, the real bean is resolved on first use,
    // by which point the whole context (including the cycle's other side) already exists.
    public WorkflowEventDispatcher(WorkflowTriggerRepository triggerRepo, WorkflowRepository workflowRepo,
                                    @Lazy WorkflowRunService runService, ProjectRepository projectRepo,
                                    FindingPresentationService findingPresentationService) {
        this.triggerRepo = triggerRepo;
        this.workflowRepo = workflowRepo;
        this.runService = runService;
        this.projectRepo = projectRepo;
        this.findingPresentationService = findingPresentationService;
    }

    public void onDetectionCreated(Detection d) { dispatch("detection.created", d.getId(), detectionSnapshot(d), d.getProjectId()); }
    public void onDetectionUpdated(Detection d) { dispatch("detection.updated", d.getId(), detectionSnapshot(d), d.getProjectId()); }
    public void onDetectionDeleted(Long detectionId, Long projectId) { dispatch("detection.deleted", detectionId, Map.of("id", detectionId), projectId); }

    public void onFindingCreated(Finding f) { dispatch("finding.created", f.getId(), findingSnapshot(f), f.getProjectId()); }
    public void onFindingUpdated(Finding f) { dispatch("finding.updated", f.getId(), findingSnapshot(f), f.getProjectId()); }
    public void onFindingDeleted(Long findingId, Long projectId) { dispatch("finding.deleted", findingId, Map.of("id", findingId), projectId); }

    /** Assets belong to an organization directly, not a project — dispatch resolves eligibility
     *  against {@code organizationId} instead of the project-lookup path Detection/Finding use
     *  (both project-only entities with no direct org column). */
    public void onAssetCreated(Asset a) { dispatchForOrg("asset.created", a.getId(), assetSnapshot(a), a.getOrganizationId()); }
    public void onAssetUpdated(Asset a) { dispatchForOrg("asset.updated", a.getId(), assetSnapshot(a), a.getOrganizationId()); }
    public void onAssetDeleted(Long assetId, Long organizationId) { dispatchForOrg("asset.deleted", assetId, Map.of("id", assetId), organizationId); }

    /** Organization has no scope above it to resolve against — only reachable from PLATFORM-scope
     *  TRIGGER_EVENT nodes. No 'deleted' — organizations are archived, never hard-deleted. */
    public void onOrganizationCreated(Organization o) { dispatchPlatformOnly("organization.created", o.getId(), organizationSnapshot(o)); }
    public void onOrganizationUpdated(Organization o) { dispatchPlatformOnly("organization.updated", o.getId(), organizationSnapshot(o)); }

    /** A project belongs directly to one organization — same dispatchForOrg path Asset already
     *  uses, just with the project's own id as both the org-owned entity and its own organizationId
     *  as the containment key. */
    public void onProjectCreated(Project p) { dispatchForOrg("project.created", p.getId(), projectSnapshot(p), p.getOrganizationId()); }
    public void onProjectUpdated(Project p) { dispatchForOrg("project.updated", p.getId(), projectSnapshot(p), p.getOrganizationId()); }
    public void onProjectDeleted(Long projectId, Long organizationId) { dispatchForOrg("project.deleted", projectId, Map.of("id", projectId), organizationId); }

    /** A platform-wide KB catalog entity, same shape as Organization — no scope to resolve
     *  against, only reachable from PLATFORM-scope TRIGGER_EVENT nodes. */
    public void onFindingTemplateCreated(FindingTemplate t) { dispatchPlatformOnly("finding_template.created", t.getId(), findingTemplateSnapshot(t)); }
    public void onFindingTemplateUpdated(FindingTemplate t) { dispatchPlatformOnly("finding_template.updated", t.getId(), findingTemplateSnapshot(t)); }
    public void onFindingTemplateDeleted(Long templateId) { dispatchPlatformOnly("finding_template.deleted", templateId, Map.of("id", templateId)); }

    /** CVE — the platform's first "external database" entity. {@code entityId} is the
     *  human-readable {@code cveId} (e.g. "CVE-2024-1234"), not the Mongo {@code _id}. No
     *  'deleted' — the upstream CVE feed is upsert-only, entries are never removed. */
    public void onCveCreated(CveEntry e) { dispatchPlatformOnly("cve.created", e.getCveId(), cveSnapshot(e)); }
    public void onCveUpdated(CveEntry e) { dispatchPlatformOnly("cve.updated", e.getCveId(), cveSnapshot(e)); }

    /** Fires *in addition to* {@link #onCveUpdated} for the same entry when it just became newly
     *  KEV-listed in this specific catalog (never for entries that fell *out* of a catalog — that's
     *  still just a plain {@code cve.updated}, not this) — {@code catalog} is {@code "cisa"} or
     *  {@code "vulncheck"}, whichever caller matches. Deliberately its own concrete event rather
     *  than folding into {@code cve.updated}: "a CVE just entered KEV" is specific and actionable
     *  in a way "some CVE field changed" isn't. */
    public void onCveKevAdded(CveEntry e, String catalog) {
        Map<String, Object> snapshot = cveSnapshot(e);
        snapshot.put("catalog", catalog);
        snapshot.put("dateAdded", "cisa".equals(catalog) ? e.getKevDateAdded() : e.getVulncheckKevDateAdded());
        dispatchPlatformOnly("cve.kev_added", e.getCveId(), snapshot);
    }

    /** A new exploit/PoC was just linked to this CVE (manual upload or manual git clone only —
     *  see {@code ExploitService}/{@code ExploitGitRunner}; the two bulk-sync sources, ExploitDB
     *  and VulnCheck XDB, don't thread per-row identity through today and are deliberately not
     *  wired here yet). {@code exploitLink} is the exploit's own origin URL and may be {@code
     *  null} for a manual ZIP upload with no external source. */
    public void onCvePocAdded(String cveId, String exploitId, String exploitName, String exploitLink) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("cveId", cveId);
        snapshot.put("exploitId", exploitId);
        snapshot.put("exploitName", exploitName);
        snapshot.put("exploitLink", exploitLink);
        dispatchPlatformOnly("cve.poc_added", cveId, snapshot);
    }

    // ── Entity snapshots — a small, curated Map per type, not the live JPA/Mongo object (avoids
    // lazy-proxy serialization issues and keeps workflow_run.context from ballooning with fields
    // nobody templates against). Field choices mirror each entity's own most-queried columns. ──

    private Map<String, Object> detectionSnapshot(Detection d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId()); m.put("title", d.getTitle()); m.put("severity", d.getSeverity());
        m.put("status", d.getStatus()); m.put("projectId", d.getProjectId()); m.put("assetId", d.getAssetId());
        return m;
    }

    private Map<String, Object> findingSnapshot(Finding f) {
        // id/code/title/priority/status/dueDate — same AQL-shaped fields the email-report path
        // uses, see FindingPresentationService#scalars. No severityLabel/severityColor here —
        // those are resolved per-EmailTemplate (see FindingPresentationService#severityDisplay),
        // and this generic trigger context isn't tied to any specific template. Note:
        // WorkflowContextFlattener index-flattens arrays into trigger.finding.affections.0.code-
        // style scalar keys once this snapshot lands in run.context, so the
        // {{#finding.affections}} repeat-block syntax (which needs a real List, not pre-flattened
        // scalars) only works from the email-report path, never from ACTION_NOTIFICATION — that's
        // fine, that node is plain-text-only by design.
        Map<String, Object> m = new LinkedHashMap<>(findingPresentationService.scalars(f));
        m.put("statusId", f.getStatusId());
        m.put("projectId", f.getProjectId());
        m.put("severity", f.getSeverity());
        m.put("fields", findingPresentationService.customFields(f));
        m.put("affections", findingPresentationService.affections(f.getId()));
        findingPresentationService.references(f.getId()).forEach((type, list) ->
            m.put("all".equals(type) ? "references" : type, list));
        return m;
    }

    private Map<String, Object> assetSnapshot(Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId()); m.put("code", a.getCode()); m.put("type", a.getType());
        m.put("identifier", a.getIdentifier()); m.put("organizationId", a.getOrganizationId());
        return m;
    }

    private Map<String, Object> organizationSnapshot(Organization o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId()); m.put("name", o.getName()); m.put("slug", o.getSlug()); m.put("status", o.getStatus());
        return m;
    }

    private Map<String, Object> projectSnapshot(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("name", p.getName()); m.put("code", p.getCode()); m.put("organizationId", p.getOrganizationId());
        return m;
    }

    private Map<String, Object> findingTemplateSnapshot(FindingTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("title", t.getTitle()); m.put("severity", t.getSeverity());
        return m;
    }

    private Map<String, Object> cveSnapshot(CveEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cveId", e.getCveId()); m.put("severity", e.getSeverity()); m.put("cvssScore", e.getCvssScore());
        m.put("kevListed", e.isKevListed()); m.put("vulncheckKevListed", e.isVulncheckKevListed());
        m.put("exploitCount", e.getExploitCount());
        return m;
    }

    private void dispatch(String eventCode, Object entityId, Map<String, Object> entity, Long projectId) {
        runAfterCommitOrNow(() -> {
            try {
                if (projectId == null) return;
                Long orgId = projectRepo.findById(projectId).map(p -> p.getOrganizationId()).orElse(null);
                fireMatching(eventCode, entityId, entity, WorkflowScope.PROJECT, projectId, orgId);
            } catch (Exception e) {
                log.error("TRIGGER_EVENT dispatch failed for {} #{}: {}", eventCode, entityId, e.getMessage(), e);
            }
        });
    }

    private void dispatchForOrg(String eventCode, Object entityId, Map<String, Object> entity, Long organizationId) {
        runAfterCommitOrNow(() -> {
            try {
                if (organizationId == null) return;
                fireMatching(eventCode, entityId, entity, null, null, organizationId);
            } catch (Exception e) {
                log.error("TRIGGER_EVENT dispatch failed for {} #{}: {}", eventCode, entityId, e.getMessage(), e);
            }
        });
    }

    /** No scope to resolve against at all — passing null/null/null through fireMatching's own
     *  eligibility switch already makes PLATFORM the only scope that can ever match (ORGANIZATION/
     *  PROJECT both require a non-null id to compare against), so no change to fireMatching itself
     *  is needed. */
    private void dispatchPlatformOnly(String eventCode, Object entityId, Map<String, Object> entity) {
        runAfterCommitOrNow(() -> {
            try {
                fireMatching(eventCode, entityId, entity, null, null, null);
            } catch (Exception e) {
                log.error("TRIGGER_EVENT dispatch failed for {} #{}: {}", eventCode, entityId, e.getMessage(), e);
            }
        });
    }

    /**
     * The {@code on*Created}/{@code on*Updated}/etc. methods above are called synchronously from
     * WITHIN the entity's own save transaction (e.g. {@code DetectionService.create}), before it
     * commits. If dispatch ran immediately, every downstream node's own DB queries — {@code
     * countByAql}, {@code listByAql}, the whole ASSIGN_VARIABLE/COUNT_COMPARE machinery — would run
     * in {@link WorkflowRunService#start}'s own REQUIRES_NEW transaction, a genuinely separate DB
     * connection that (under Postgres' default READ COMMITTED isolation) simply cannot see rows
     * another transaction hasn't committed yet — so a workflow reacting to "detection just created"
     * could never actually find that very detection via a fresh query (only the static {@code
     * trigger.detection.*} snapshot fields, captured in memory before the query ever runs, would be
     * correct — a real, reproduced bug: {@code id == {{trigger.detection.id}}} matched zero rows).
     * Deferring the real dispatch to run only {@link TransactionSynchronization#afterCommit()} (a
     * standard Spring pattern for exactly this "wait until my caller's save is durable" need) fixes
     * it at the root: by the time any workflow node's query runs, the triggering row was already
     * committed by an entirely separate, already-finished transaction, so it's visible like any
     * other committed data. Runs immediately, unchanged, when there's no active transaction to wait
     * on (defensive — every real caller has one, but nothing here requires it).
     */
    private void runAfterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** {@code exactScopeKind}/{@code exactScopeId} is the PROJECT-scope match (null for Asset,
     *  which has no project of its own); {@code orgId} is always the ORGANIZATION-scope match.
     *  PLATFORM-scope workflows match everything, same as the other trigger types. */
    private void fireMatching(String eventCode, Object entityId, Map<String, Object> entity,
                               String exactScopeKind, Long exactScopeId, Long orgId) {
        List<WorkflowTrigger> candidates = triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT);
        if (candidates.isEmpty()) return;

        // Every eventCode is "<type>.<action>" (validated at save time — see
        // WorkflowGraphValidator.EVENT_CODES_BY_SCOPE) — the type prefix IS the entity's AQL/tag
        // type name, reused verbatim as the context key so trigger.detection/trigger.finding/
        // trigger.cve/etc. line up with every other place this codebase names an entity type.
        String entityTypeKey = eventCode.substring(0, eventCode.indexOf('.'));
        Map<String, Object> triggerContext = Map.of("eventCode", eventCode, "entityId", entityId, entityTypeKey, entity);
        for (WorkflowTrigger trigger : candidates) {
            if (!eventCode.equals(readConfigField(trigger.getConfig(), "eventCode"))) continue;
            Workflow wf = workflowRepo.findById(trigger.getWorkflowId()).orElse(null);
            if (wf == null || !"active".equals(wf.getStatus())) continue;
            boolean eligible = switch (wf.getScopeKind()) {
                case WorkflowScope.PLATFORM -> true;
                case WorkflowScope.PROJECT -> exactScopeKind != null && wf.getScopeId().equals(exactScopeId);
                case WorkflowScope.ORGANIZATION -> orgId != null && wf.getScopeId().equals(orgId);
                default -> false;
            };
            if (!eligible) continue;
            try {
                log.info("TRIGGER_EVENT({}) → workflow {} (node {}, entity #{})",
                    eventCode, wf.getId(), trigger.getNodeId(), entityId);
                runService.start(wf.getId(), trigger.getNodeId(), triggerContext, "event", null);
            } catch (Exception e) {
                // ERROR, not WARN — a workflow that fails on every single matching event is a
                // silent automation outage, not a transient hiccup worth burying. e is passed as
                // the final varargs so SLF4J logs its full stack trace — the message alone, for an
                // UnexpectedRollbackException, says only that *something* upstream failed, never
                // what: see WorkflowRunService#executeNode's own now-logged catch for that.
                log.error("TRIGGER_EVENT run failed for workflow {} trigger {}: {}", wf.getId(), trigger.getId(), e.getMessage(), e);
            }
        }
    }

    private String readConfigField(String configJson, String field) {
        try {
            JsonNode node = MAPPER.readTree(configJson).get(field);
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
