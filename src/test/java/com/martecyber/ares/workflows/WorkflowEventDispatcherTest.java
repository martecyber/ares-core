package com.martecyber.ares.workflows;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingPresentationService;
import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test — the dispatcher's own eligibility-matching logic (a single {@code
 * eventCode} string match, scope match, active-only) is independent of real persistence.
 *
 * Every mock is built and fully stubbed in its OWN statement, never inline inside another
 * {@code when(...).thenReturn(...)} call — nesting a second mock's construction/stubbing inside
 * an unfinished outer stub (e.g. {@code when(x).thenReturn(Optional.of(helperThatStubsAMock()))})
 * corrupts Mockito's global "ongoing stubbing" state and fails with a confusing
 * {@code UnfinishedStubbing} error on a totally unrelated line.
 */
class WorkflowEventDispatcherTest {

    private WorkflowTriggerRepository triggerRepo;
    private WorkflowRepository workflowRepo;
    private WorkflowRunService runService;
    private ProjectRepository projectRepo;
    private FindingPresentationService findingPresentationService;
    private WorkflowEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        triggerRepo = mock(WorkflowTriggerRepository.class);
        workflowRepo = mock(WorkflowRepository.class);
        runService = mock(WorkflowRunService.class);
        projectRepo = mock(ProjectRepository.class);
        // Left unstubbed in most tests: Mockito's default answer returns an empty Map/List for
        // scalars(...)/affections(...)/etc. (not null), so findingSnapshot's fields drawn from
        // them are simply absent — exactly what a test that doesn't care about those fields
        // wants without having to stub anything.
        findingPresentationService = mock(FindingPresentationService.class);
        dispatcher = new WorkflowEventDispatcher(triggerRepo, workflowRepo, runService, projectRepo,
            findingPresentationService);
    }

    private WorkflowTrigger trigger(long workflowId, String nodeId, String eventCode) {
        WorkflowTrigger t = new WorkflowTrigger();
        t.setWorkflowId(workflowId);
        t.setNodeId(nodeId);
        t.setTriggerType(WorkflowTriggerType.EVENT);
        t.setEnabled(true);
        t.setConfig("{\"eventCode\":\"" + eventCode + "\"}");
        return t;
    }

    private Workflow workflow(long id, String scopeKind, long scopeId, String status) {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn(id);
        when(wf.getScopeKind()).thenReturn(scopeKind);
        when(wf.getScopeId()).thenReturn(scopeId);
        when(wf.getStatus()).thenReturn(status);
        return wf;
    }

    private void stubWorkflow(long id, String scopeKind, long scopeId, String status) {
        Workflow wf = workflow(id, scopeKind, scopeId, status);
        when(workflowRepo.findById(id)).thenReturn(Optional.of(wf));
    }

    private Detection detection(long id, long projectId) {
        Detection d = mock(Detection.class);
        when(d.getId()).thenReturn(id);
        when(d.getProjectId()).thenReturn(projectId);
        return d;
    }

    private void stubProject(long id, long orgId) {
        Project p = mock(Project.class);
        when(p.getOrganizationId()).thenReturn(orgId);
        when(projectRepo.findById(id)).thenReturn(Optional.of(p));
    }

    @Test
    void firesForAMatchingProjectScopedWorkflow() {
        WorkflowTrigger t = trigger(10, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(10, WorkflowScope.PROJECT, 5, "active");
        stubProject(5, 99);

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(runService).start(eq(10L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void doesNotFireForADifferentProject() {
        WorkflowTrigger t = trigger(10, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(10, WorkflowScope.PROJECT, 5, "active");
        stubProject(7, 99);

        dispatcher.onDetectionCreated(detection(1, 7)); // different project (7, not 5)

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void firesForAMatchingOrganizationScopedWorkflow() {
        WorkflowTrigger t = trigger(11, "n1", "finding.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(11, WorkflowScope.ORGANIZATION, 99, "active");
        stubProject(5, 99);

        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(2L);
        when(f.getProjectId()).thenReturn(5L);
        dispatcher.onFindingCreated(f);

        verify(runService).start(eq(11L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findingSnapshotCarriesScalarsAndAffectionsFromThePresentationService() {
        WorkflowTrigger t = trigger(11, "n1", "finding.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(11, WorkflowScope.ORGANIZATION, 99, "active");
        stubProject(5, 99);

        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(2L);
        when(f.getProjectId()).thenReturn(5L);
        when(f.getSeverity()).thenReturn("critical");
        // FindingPresentationService's own resolution logic (affection formatting) is unit-tested
        // separately — this test only cares that the dispatcher plumbs its results into the
        // trigger.finding.* snapshot correctly. No severityLabel/severityColor here: those are
        // resolved per-EmailTemplate (see FindingPresentationService#severityDisplay), not part
        // of this generic trigger context — see findingSnapshot's own comment.
        when(findingPresentationService.scalars(f)).thenReturn(Map.of("code", "F-002"));
        List<Map<String, Object>> affectionsList = List.of(Map.of("code", "AFF-001", "title", "Outdated TLS"));
        when(findingPresentationService.affections(2L)).thenReturn(affectionsList);

        dispatcher.onFindingCreated(f);

        var captor = ArgumentCaptor.forClass(Map.class);
        verify(runService).start(eq(11L), eq("n1"), captor.capture(), eq("event"), eq((Long) null));
        // The entity snapshot is nested under its type-prefix key (trigger.finding.* templating —
        // see fireMatching), not flattened at the trigger context's top level.
        Map<String, Object> finding = (Map<String, Object>) captor.getValue().get("finding");
        assertEquals("F-002", finding.get("code"));
        assertEquals(affectionsList, finding.get("affections"));
    }

    @Test
    void doesNotFireForADifferentOrganization() {
        WorkflowTrigger t = trigger(11, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(11, WorkflowScope.ORGANIZATION, 1, "active");
        stubProject(5, 2); // org 2, not 1

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void platformScopedWorkflowMatchesAnyProject() {
        WorkflowTrigger t = trigger(12, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(12, WorkflowScope.PLATFORM, 0, "active");
        stubProject(5, 99);

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(runService).start(eq(12L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void doesNotFireForADraftWorkflow() {
        WorkflowTrigger t = trigger(13, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(13, WorkflowScope.PLATFORM, 0, "draft");

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotFireForAMismatchedEventCode() {
        WorkflowTrigger t = trigger(14, "n1", "finding.created"); // listens for finding, not detection
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(workflowRepo, never()).findById(anyLong());
        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void assetUsesOrganizationDirectlyNotAProjectLookup() {
        WorkflowTrigger t = trigger(15, "n1", "asset.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(15, WorkflowScope.ORGANIZATION, 42, "active");

        Asset a = mock(Asset.class);
        when(a.getId()).thenReturn(3L);
        when(a.getOrganizationId()).thenReturn(42L);
        dispatcher.onAssetCreated(a);

        verify(runService).start(eq(15L), eq("n1"), any(), eq("event"), eq((Long) null));
        verifyNoInteractions(projectRepo);
    }

    @Test
    void aProjectScopedTriggerNeverMatchesAnAssetEvent() {
        // Asset has no project of its own — PROJECT-scope workflows must never fire for it.
        WorkflowTrigger t = trigger(16, "n1", "asset.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(16, WorkflowScope.PROJECT, 5, "active");

        Asset a = mock(Asset.class);
        when(a.getId()).thenReturn(3L);
        when(a.getOrganizationId()).thenReturn(5L); // deliberately same numeric id as a project, to prove no accidental match
        dispatcher.onAssetCreated(a);

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void updatedEventCodeFiresAnUpdatedOnlyTrigger() {
        WorkflowTrigger t = trigger(22, "n1", "detection.updated");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(22, WorkflowScope.PLATFORM, 0, "active");
        stubProject(5, 99);

        dispatcher.onDetectionUpdated(detection(1, 5));

        verify(runService).start(eq(22L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void createdOnlyTriggerDoesNotFireOnUpdate() {
        WorkflowTrigger t = trigger(23, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(23, WorkflowScope.PLATFORM, 0, "active");
        stubProject(5, 99);

        dispatcher.onDetectionUpdated(detection(1, 5));

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void deletedEventFiresWithNoLiveEntityNeeded() {
        WorkflowTrigger t = trigger(25, "n1", "finding.deleted");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(25, WorkflowScope.PLATFORM, 0, "active");
        stubProject(5, 99);

        dispatcher.onFindingDeleted(2L, 5L);

        verify(runService).start(eq(25L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void assetUpdatedAndDeletedUseOrganizationDirectly() {
        WorkflowTrigger updated = trigger(26, "n1", "asset.updated");
        WorkflowTrigger deleted = trigger(27, "n2", "asset.deleted");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(updated, deleted));
        stubWorkflow(26, WorkflowScope.ORGANIZATION, 42, "active");
        stubWorkflow(27, WorkflowScope.ORGANIZATION, 42, "active");

        Asset a = mock(Asset.class);
        when(a.getId()).thenReturn(3L);
        when(a.getOrganizationId()).thenReturn(42L);
        dispatcher.onAssetUpdated(a);
        verify(runService).start(eq(26L), eq("n1"), any(), eq("event"), eq((Long) null));
        verify(runService, never()).start(eq(27L), any(), any(), any(), any());

        dispatcher.onAssetDeleted(3L, 42L);
        verify(runService).start(eq(27L), eq("n2"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void organizationEventsOnlyFireForPlatformScopedWorkflows() {
        WorkflowTrigger platformSub = trigger(30, "n1", "organization.created");
        WorkflowTrigger orgSub = trigger(31, "n2", "organization.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(platformSub, orgSub));
        stubWorkflow(30, WorkflowScope.PLATFORM, 0, "active");
        stubWorkflow(31, WorkflowScope.ORGANIZATION, 42, "active");

        com.martecyber.ares.organizations.Organization o = mock(com.martecyber.ares.organizations.Organization.class);
        when(o.getId()).thenReturn(42L);
        dispatcher.onOrganizationCreated(o);

        verify(runService).start(eq(30L), eq("n1"), any(), eq("event"), eq((Long) null));
        verify(runService, never()).start(eq(31L), any(), any(), any(), any());
        verifyNoInteractions(projectRepo);
    }

    @Test
    void projectEventsUseOrganizationDirectlyLikeAsset() {
        WorkflowTrigger orgSub = trigger(32, "n1", "project.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(orgSub));
        stubWorkflow(32, WorkflowScope.ORGANIZATION, 42, "active");

        Project p = mock(Project.class);
        when(p.getId()).thenReturn(7L);
        when(p.getOrganizationId()).thenReturn(42L);
        dispatcher.onProjectCreated(p);

        verify(runService).start(eq(32L), eq("n1"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void projectEventsDoNotFireForADifferentOrganization() {
        WorkflowTrigger orgSub = trigger(33, "n1", "project.updated");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(orgSub));
        stubWorkflow(33, WorkflowScope.ORGANIZATION, 1, "active");

        Project p = mock(Project.class);
        when(p.getId()).thenReturn(7L);
        when(p.getOrganizationId()).thenReturn(2L); // different org
        dispatcher.onProjectUpdated(p);

        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void findingTemplateEventsOnlyFireForPlatformScopedWorkflows() {
        WorkflowTrigger platformSub = trigger(34, "n1", "finding_template.deleted");
        WorkflowTrigger projectSub = trigger(35, "n2", "finding_template.deleted");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(platformSub, projectSub));
        stubWorkflow(34, WorkflowScope.PLATFORM, 0, "active");
        stubWorkflow(35, WorkflowScope.PROJECT, 5, "active");

        dispatcher.onFindingTemplateDeleted(9L);

        verify(runService).start(eq(34L), eq("n1"), any(), eq("event"), eq((Long) null));
        verify(runService, never()).start(eq(35L), any(), any(), any(), any());
    }

    @Test
    void oneWorkflowFailingDoesNotBlockTheOthers() {
        WorkflowTrigger t1 = trigger(20, "n1", "detection.created");
        WorkflowTrigger t2 = trigger(21, "n2", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t1, t2));
        stubWorkflow(20, WorkflowScope.PLATFORM, 0, "active");
        stubWorkflow(21, WorkflowScope.PLATFORM, 0, "active");
        stubProject(5, 99);
        doThrow(new RuntimeException("boom")).when(runService).start(eq(20L), any(), any(), any(), any());

        dispatcher.onDetectionCreated(detection(1, 5));

        verify(runService).start(eq(21L), eq("n2"), any(), eq("event"), eq((Long) null));
    }

    @Test
    void noEventTriggersAtAllIsANoOp() {
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of());
        dispatcher.onDetectionCreated(detection(1, 5));
        verifyNoInteractions(workflowRepo, runService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void triggerContextCarriesTheFullEntitySnapshot() {
        WorkflowTrigger t = trigger(40, "n1", "detection.created");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(40, WorkflowScope.PLATFORM, 0, "active");

        Detection d = mock(Detection.class);
        when(d.getId()).thenReturn(1L);
        when(d.getProjectId()).thenReturn(5L);
        when(d.getTitle()).thenReturn("XSS in login form");
        when(d.getSeverity()).thenReturn("high");
        dispatcher.onDetectionCreated(d);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runService).start(eq(40L), eq("n1"), captor.capture(), eq("event"), eq((Long) null));
        Map<String, Object> context = captor.getValue();
        assertEquals("detection.created", context.get("eventCode"));
        assertEquals(1L, context.get("entityId"));
        Map<String, Object> entity = (Map<String, Object>) context.get("detection");
        assertEquals("XSS in login form", entity.get("title"));
        assertEquals("high", entity.get("severity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cveKevAddedCarriesTheCatalogAndDate() {
        WorkflowTrigger t = trigger(41, "n1", "cve.kev_added");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(41, WorkflowScope.PLATFORM, 0, "active");

        CveEntry e = new CveEntry();
        e.setCveId("CVE-2024-9999");
        e.setKevListed(true);
        e.setKevDateAdded(LocalDate.of(2026, 1, 15));
        dispatcher.onCveKevAdded(e, "cisa");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runService).start(eq(41L), eq("n1"), captor.capture(), eq("event"), eq((Long) null));
        Map<String, Object> context = captor.getValue();
        assertEquals("CVE-2024-9999", context.get("entityId"));
        Map<String, Object> entity = (Map<String, Object>) context.get("cve");
        assertEquals("cisa", entity.get("catalog"));
        assertEquals(LocalDate.of(2026, 1, 15), entity.get("dateAdded"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cvePocAddedCarriesTheExploitDetails() {
        WorkflowTrigger t = trigger(42, "n1", "cve.poc_added");
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenReturn(List.of(t));
        stubWorkflow(42, WorkflowScope.PLATFORM, 0, "active");

        dispatcher.onCvePocAdded("CVE-2024-8888", "abc123", "CoolExploit", "https://github.com/x/y");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runService).start(eq(42L), eq("n1"), captor.capture(), eq("event"), eq((Long) null));
        Map<String, Object> context = captor.getValue();
        assertEquals("CVE-2024-8888", context.get("entityId"));
        Map<String, Object> entity = (Map<String, Object>) context.get("cve");
        assertEquals("abc123", entity.get("exploitId"));
        assertEquals("CoolExploit", entity.get("exploitName"));
        assertEquals("https://github.com/x/y", entity.get("exploitLink"));
    }

    @Test
    void topLevelExceptionNeverEscapesTheCaller() {
        when(triggerRepo.findByTriggerTypeAndEnabledTrue(WorkflowTriggerType.EVENT)).thenThrow(new RuntimeException("db down"));
        assertEquals(0, countThrown(() -> dispatcher.onDetectionCreated(detection(1, 5))));
    }

    private int countThrown(Runnable r) {
        try { r.run(); return 0; } catch (Exception e) { return 1; }
    }
}
