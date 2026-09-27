package com.martecyber.ares.agents.tasks;

import com.martecyber.ares.agents.Agent;
import com.martecyber.ares.agents.AgentRepository;
import com.martecyber.ares.agents.pools.AgentPoolRepository;
import com.martecyber.ares.agents.pools.AgentPoolService;
import com.martecyber.ares.agents.schedule.AgentScheduleRunService;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.*;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.imports.ImportResult;
import com.martecyber.ares.imports.ImportService;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.rules.EngagementRuleEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link AgentTaskService} — task creation/batching, the
 *  claim/dispatch (SKIP LOCKED-backed) flow, {@code complete}'s "exit-code-nonzero-with-no-
 *  output" tool-failure heuristic, cancel/fail terminal-state no-ops, and the queue-recovery
 *  helpers (reconcile/orphan/timeout). No Spring context. */
class AgentTaskServiceTest {

    private AgentTaskRepository taskRepo;
    private AgentPoolService poolService;
    private AgentPoolRepository poolRepo;
    private AgentToolSpecRegistry agentToolSpecRegistry;
    private ImportService importService;
    private ProjectRepository projectRepo;
    private TargetResolver resolver;
    private AgentRepository agentRepo;
    private AgentScheduleRunService scheduleRuns;
    private EngagementRuleEnforcer ruleEnforcer;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(AgentTaskRepository.class);
        poolService = mock(AgentPoolService.class);
        poolRepo = mock(AgentPoolRepository.class);
        agentToolSpecRegistry = mock(AgentToolSpecRegistry.class);
        importService = mock(ImportService.class);
        projectRepo = mock(ProjectRepository.class);
        resolver = mock(TargetResolver.class);
        agentRepo = mock(AgentRepository.class);
        scheduleRuns = mock(AgentScheduleRunService.class);
        ruleEnforcer = mock(EngagementRuleEnforcer.class);
        service = new AgentTaskService(taskRepo, poolService, poolRepo, agentToolSpecRegistry, importService,
            projectRepo, resolver, agentRepo, scheduleRuns, ruleEnforcer);

        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resolver.resolveInto(anyLong(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(resolver.resolveInto(anyLong(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
    }

    private AgentTask task(Long id, Long projectId, Long agentId, String status) {
        AgentTask t = new AgentTask();
        ReflectionTestUtils.setField(t, "id", id);
        t.setProjectId(projectId);
        t.setAgentId(agentId);
        t.setStatus(status);
        t.setTool("nmap");
        t.setFormat("default");
        when(taskRepo.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private Project project(Long id, Long orgId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setOrganizationId(orgId);
        when(projectRepo.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private Agent agent(Long id, String capsJson) {
        Agent a = new Agent();
        ReflectionTestUtils.setField(a, "id", id);
        a.setName("Agent " + id);
        a.setCapabilities(capsJson);
        when(agentRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    // ── createAll() ──────────────────────────────────────────────────

    @Test
    void createAllRequiresPoolIdAndTool() {
        assertThrows(ResponseStatusException.class, () -> service.createAll(1L, new CreateTask(null, null, null, null, null, null, null, null, null, false, null), 7L));
    }

    @Test
    void createAllThrowsNotFoundForAnUnknownProject() {
        when(projectRepo.findById(1L)).thenReturn(Optional.empty());
        var req = new CreateTask(null, 2L, "nmap", null, null, null, null, null, null, false, null);
        assertThrows(NotFoundException.class, () -> service.createAll(1L, req, 7L));
    }

    @Test
    void createAllRejectsAPoolNotGrantedToTheProject() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(2L, 9L, 1L)).thenReturn(false);
        var req = new CreateTask(null, 2L, "nmap", null, null, null, null, null, null, false, null);
        assertThrows(ResponseStatusException.class, () -> service.createAll(1L, req, 7L));
    }

    @Test
    void createAllAppliesEngagementRulesAndValidatesArgsBeforeSaving() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(2L, 9L, 1L)).thenReturn(true);
        var req = new CreateTask(null, 2L, "nmap", null, Map.of("targets", List.of("1.2.3.4")), null, null, null, null, false, null);

        service.createAll(1L, req, 7L);

        verify(ruleEnforcer).applyToTaskArgs(eq(1L), eq("nmap"), any(), eq(false));
        verify(agentToolSpecRegistry).validateArgs(eq("nmap"), any());
        verify(taskRepo).save(any());
    }

    @Test
    void createAllDefaultsFormatToDefaultAndPriorityToZero() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        var req = new CreateTask(null, 2L, "nmap", null, Map.of(), null, null, null, null, false, null);
        var dto = service.createAll(1L, req, 7L).get(0);
        assertEquals("default", dto.format());
        assertEquals(0, dto.priority());
    }

    @Test
    void createAllSplitsIntoBatchesWhenTargetsExceedBatchSize() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        var req = new CreateTask("Scan", 2L, "nmap", null,
            Map.of("targets", List.of("1.1.1.1", "2.2.2.2", "3.3.3.3")), null, null, null, 2, false, null);

        var result = service.createAll(1L, req, 7L);

        assertEquals(2, result.size());
        assertEquals("Scan (1/2)", result.get(0).name());
        assertEquals("Scan (2/2)", result.get(1).name());
    }

    @Test
    void createAllDoesNotSplitWhenTargetsFitInOneBatch() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        var req = new CreateTask(null, 2L, "nmap", null,
            Map.of("targets", List.of("1.1.1.1")), null, null, null, 5, false, null);
        assertEquals(1, service.createAll(1L, req, 7L).size());
    }

    /** Regression: a selector resolving to more than TargetResolver's MAX_TARGETS cap must not
     *  fail outright when batchSize is configured — batching exists precisely to subdivide a
     *  result set that large into several tasks, so the resolver's cap has to be skipped
     *  (enforceLimit=false) whenever batchSize is set, and still applied (enforceLimit=true)
     *  when it isn't. */
    @Test
    void createAllSkipsTheTargetCountCapWhenBatchSizeIsConfigured() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        var req = new CreateTask(null, 2L, "nmap", null, Map.of("targets", List.of("1.1.1.1")),
            null, null, null, 1000, false, null);

        service.createAll(1L, req, 7L);

        verify(resolver).resolveInto(eq(1L), any(), eq(false));
    }

    @Test
    void createAllEnforcesTheTargetCountCapWhenNotBatched() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        var req = new CreateTask(null, 2L, "nmap", null, Map.of("targets", List.of("1.1.1.1")),
            null, null, null, null, false, null);

        service.createAll(1L, req, 7L);

        verify(resolver).resolveInto(eq(1L), any(), eq(true));
    }

    // ── maxTargets: 1 (wpscan/ffuf-shaped tools) — the ONLY enforcement for a manually-created
    // or schedule-fired task, since WorkflowGraphValidator's equivalent batchSize check never
    // runs outside a workflow node. ────────────────────────────────────────────────────────

    private static final AgentToolSpec SINGLE_TARGET_TOOL = new AgentToolSpec(
        "single-target-tool", "Single Target Tool", "single-target-tool", "default", Set.of(), Set.of(), 1, "single-flag",
        "--target", null, null, false, null, List.of());

    @Test
    void createAllRejectsMoreThanMaxTargetsWhenNotBatched() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        when(agentToolSpecRegistry.find("single-target-tool")).thenReturn(Optional.of(SINGLE_TARGET_TOOL));
        var req = new CreateTask(null, 2L, "single-target-tool", null,
            Map.of("targets", List.of("https://a.example.com", "https://b.example.com")),
            null, null, null, null, false, null);

        var ex = assertThrows(ResponseStatusException.class, () -> service.createAll(1L, req, 7L));
        assertTrue(ex.getReason().contains("single-target-tool"));
    }

    @Test
    void createAllRejectsABatchSizeAboveMaxTargets() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        when(agentToolSpecRegistry.find("single-target-tool")).thenReturn(Optional.of(SINGLE_TARGET_TOOL));
        var req = new CreateTask(null, 2L, "single-target-tool", null, Map.of("targets", List.of("https://a.example.com")),
            null, null, null, 5, false, null);

        var ex = assertThrows(ResponseStatusException.class, () -> service.createAll(1L, req, 7L));
        assertTrue(ex.getReason().contains("batchSize"));
    }

    @Test
    void createAllAllowsExactlyMaxTargetsForABatchSizeOneTool() {
        project(1L, 9L);
        when(poolService.isGrantedToProject(any(), any(), any())).thenReturn(true);
        when(agentToolSpecRegistry.find("single-target-tool")).thenReturn(Optional.of(SINGLE_TARGET_TOOL));
        var req = new CreateTask(null, 2L, "single-target-tool", null, Map.of("targets", List.of("https://a.example.com")),
            null, null, null, 1, false, null);

        assertDoesNotThrow(() -> service.createAll(1L, req, 7L));
    }

    // ── cancel() ─────────────────────────────────────────────────────

    @Test
    void cancelThrowsNotFoundWhenTheTaskBelongsToADifferentProject() {
        task(1L, 999L, null, "pending");
        assertThrows(NotFoundException.class, () -> service.cancel(1L, 1L));
    }

    @Test
    void cancelIsANoOpForAnAlreadyTerminalTask() {
        AgentTask t = task(1L, 1L, null, "completed");
        service.cancel(1L, 1L);
        assertEquals("completed", t.getStatus());
        verifyNoInteractions(scheduleRuns);
    }

    @Test
    void cancelSetsCancelledStatusAndComputesDuration() {
        AgentTask t = task(1L, 1L, null, "running");
        t.setStartedAt(OffsetDateTime.now().minusMinutes(5));
        service.cancel(1L, 1L);
        assertEquals("cancelled", t.getStatus());
        assertNotNull(t.getActualDurationMs());
        verify(scheduleRuns).onTaskTerminal(t);
    }

    // ── claimNext() / claimNextBatch() ────────────────────────────────

    @Test
    void claimNextReturnsEmptyWhenTheAgentHasNoAvailableTools() {
        agent(1L, null);
        assertTrue(service.claimNext(1L).isEmpty());
        verifyNoInteractions(taskRepo);
    }

    @Test
    void claimNextReturnsEmptyWhenNothingIsPending() {
        agent(1L, "[{\"tool\":\"nmap\"}]");
        when(taskRepo.pickNextPendingForAgent(eq(1L), any(), any())).thenReturn(null);
        assertTrue(service.claimNext(1L).isEmpty());
    }

    @Test
    void claimNextAssignsTheTaskAndMarksItDispatched() {
        agent(1L, "[{\"tool\":\"nmap\"}]");
        AgentTask t = task(5L, 1L, null, "pending");
        when(taskRepo.pickNextPendingForAgent(eq(1L), any(), any())).thenReturn(5L);

        var result = service.claimNext(1L);

        assertTrue(result.isPresent());
        assertEquals("dispatched", t.getStatus());
        assertEquals(1L, t.getAgentId());
        assertNotNull(t.getDispatchedAt());
    }

    @Test
    void claimNextBatchReturnsEmptyForANonPositiveCount() {
        assertTrue(service.claimNextBatch(1L, 0).isEmpty());
        verifyNoInteractions(agentRepo);
    }

    @Test
    void claimNextBatchSkipsIdsThatNoLongerResolveToARow() {
        agent(1L, "[{\"tool\":\"nmap\"}]");
        task(5L, 1L, null, "pending");
        when(taskRepo.pickNextNPendingForAgent(eq(1L), any(), any(), eq(2)))
            .thenReturn(List.of(5L, 999L)); // 999 vanished (raced away)

        var result = service.claimNextBatch(1L, 2);

        assertEquals(1, result.size());
    }

    @Test
    void hasPendingForAgentIsFalseWithoutAvailableTools() {
        agent(1L, "");
        assertFalse(service.hasPendingForAgent(1L));
        verifyNoInteractions(taskRepo);
    }

    @Test
    void hasPendingForAgentDelegatesWhenToolsAreAvailable() {
        agent(1L, "[{\"tool\":\"nmap\"}]");
        when(taskRepo.hasPendingForAgent(eq(1L), any(), any())).thenReturn(true);
        assertTrue(service.hasPendingForAgent(1L));
    }

    @Test
    void availableToolsIsEmptyForMalformedCapabilitiesJsonRatherThanThrowing() {
        agent(1L, "not json");
        // The parse failure is caught internally and treated as "no tools" — never propagates.
        assertFalse(service.hasPendingForAgent(1L));
        verifyNoInteractions(taskRepo);
    }

    // ── markStarted() ────────────────────────────────────────────────

    @Test
    void markStartedIsANoOpUnlessCurrentlyDispatched() {
        AgentTask t = task(1L, 1L, 9L, "pending");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        service.markStarted(9L, 1L);
        assertEquals("pending", t.getStatus());
    }

    @Test
    void markStartedTransitionsDispatchedToRunning() {
        AgentTask t = task(1L, 1L, 9L, "dispatched");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        service.markStarted(9L, 1L);
        assertEquals("running", t.getStatus());
        assertNotNull(t.getStartedAt());
    }

    @Test
    void markStartedThrowsWhenTheTaskIsNotAssignedToThisAgent() {
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.markStarted(9L, 1L));
    }

    // ── complete() ───────────────────────────────────────────────────

    private ImportResult importResult(boolean success, String message) {
        ImportResult r = new ImportResult();
        r.setSuccess(success);
        r.setMessage(message);
        return r;
    }

    /** {@code startImport}'s 9th (last) arg is the completion callback {@code complete()} passes
     *  to apply the finished import's outcome later — stubs the mock to invoke it immediately
     *  with {@code result}, simulating "the background import finished instantly" so these tests
     *  can assert the task's FINAL state synchronously, same as before this became non-blocking.
     *  Requires {@code taskRepo.findById(taskId)} to also resolve {@code t} — {@link
     *  #finishAfterImport} re-loads the task fresh rather than reusing {@code complete()}'s
     *  (by-then-detached, on a real DB) entity. */
    @SuppressWarnings("unchecked")
    private void stubStartImportToFinishWith(AgentTask t, Long taskId, ImportResult result) {
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(t));
        doAnswer(inv -> {
            Consumer<ImportResult> onComplete = inv.getArgument(8);
            if (onComplete != null) onComplete.accept(result);
            return null;
        }).when(importService).startImport(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void completeMarksTheTaskCompletedOnASuccessfulImport() {
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        // The stub above invokes the completion callback synchronously (same thread), so by the
        // time complete() returns here, finishAfterImport has already run too — in production,
        // on a real background thread, complete()'s own return value would still show
        // "uploading" at this point; only t's eventual (later) state is asserted here.
        service.complete(9L, 1L, "output".getBytes(), 0, null, "10.0.0.5");

        assertEquals("completed", t.getStatus());
        verify(scheduleRuns).onTaskTerminal(t);
    }

    @Test
    void completeTreatsNonZeroExitWithNoOutputAndStderrAsAFailureDespiteImportSucceeding() {
        // The "import succeeded" is only true because an empty file produces 0 findings —
        // this must be reclassified as a tool failure so it doesn't look like a silent success.
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        service.complete(9L, 1L, new byte[0], 1, "connection refused", null);

        assertEquals("failed", t.getStatus());
        assertTrue(t.getError().contains("connection refused"));
    }

    @Test
    void completeStaysCompletedWhenExitNonZeroButThereIsOutput() {
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        service.complete(9L, 1L, "partial output".getBytes(), 1, "warning", null);

        assertEquals("completed", t.getStatus());
    }

    @Test
    void completeStaysCompletedWhenNoOutputButExitCodeIsZero() {
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        service.complete(9L, 1L, new byte[0], 0, null, null);

        assertEquals("completed", t.getStatus());
    }

    @Test
    void completeMarksFailedWhenImportItselfFails() {
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(false, "no parser found"));

        service.complete(9L, 1L, "x".getBytes(), 0, null, null);

        assertEquals("failed", t.getStatus());
        assertEquals("no parser found", t.getError());
    }

    @Test
    void completeMarksFailedWhenImportThrows() {
        // A bad tool/plugin id fails synchronously, inside startImport itself, before anything
        // is queued — complete() must catch that and finish the task as failed right away
        // (finishAfterImport's startupError path), not leave it stuck in "uploading" forever.
        AgentTask t = task(1L, 1L, 9L, "running");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t));
        when(importService.startImport(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenThrow(new RuntimeException("boom"));

        service.complete(9L, 1L, "x".getBytes(), 0, null, null);

        assertEquals("failed", t.getStatus());
        assertEquals("boom", t.getError());
    }

    @Test
    void completeKeepsTheStoredSourceIpWhenTheAgentDoesNotReportOne() {
        AgentTask t = task(1L, 1L, 9L, "running");
        t.setSourceIp("1.1.1.1");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        service.complete(9L, 1L, "x".getBytes(), 0, null, "  ");

        assertEquals("1.1.1.1", t.getSourceIp());
        verify(importService).startImport(any(), any(), any(), any(), any(), eq("1.1.1.1"), any(), anyBoolean(), any());
    }

    @Test
    void completeExtractsScopeFilterDisabledFromTheTasksStoredArgs() {
        AgentTask t = task(1L, 1L, 9L, "running");
        t.setArgs("{\"scopeFilterDisabled\":true}");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        stubStartImportToFinishWith(t, 1L, importResult(true, null));

        service.complete(9L, 1L, "x".getBytes(), 0, null, null);

        assertEquals("completed", t.getStatus());
        verify(importService).startImport(any(), any(), any(), any(), any(), any(), any(), eq(true), any());
    }

    // ── fail() ───────────────────────────────────────────────────────

    @Test
    void failThrowsNotFoundForAnUnknownTask() {
        when(taskRepo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.fail(9L, 1L, "err"));
    }

    @Test
    void failIsANoOpForAnAlreadyTerminalTask() {
        AgentTask t = task(1L, 1L, 9L, "cancelled");
        service.fail(9L, 1L, "err");
        assertEquals("cancelled", t.getStatus());
    }

    @Test
    void failSetsFailedStatusAndErrorMessage() {
        AgentTask t = task(1L, 1L, 9L, "running");
        service.fail(9L, 1L, "boom");
        assertEquals("failed", t.getStatus());
        assertEquals("boom", t.getError());
        verify(scheduleRuns).onTaskTerminal(t);
    }

    // ── isCancelled() ────────────────────────────────────────────────

    @Test
    void isCancelledIsTrueOnlyWhenTheTasksStatusIsCancelled() {
        AgentTask t = task(1L, 1L, 9L, "cancelled");
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.of(t));
        assertTrue(service.isCancelled(9L, 1L));
    }

    @Test
    void isCancelledIsFalseForUnknownOrUnownedTasks() {
        when(taskRepo.findByIdAndAgentId(1L, 9L)).thenReturn(Optional.empty());
        assertFalse(service.isCancelled(9L, 1L));
    }

    // ── reconcileAgentTasks() ────────────────────────────────────────

    @Test
    void reconcileAgentTasksSkipsWhenActiveIdsIsNull() {
        service.reconcileAgentTasks(1L, null);
        verifyNoInteractions(taskRepo);
    }

    @Test
    void reconcileAgentTasksResetsAllWhenTheAgentReportsNoneActive() {
        service.reconcileAgentTasks(1L, List.of());
        verify(taskRepo).resetAllDroppedByAgent(eq(1L), any());
        verify(taskRepo, never()).resetDroppedByAgent(any(), any());
    }

    @Test
    void reconcileAgentTasksResetsOnlyTasksNotInTheActiveSet() {
        service.reconcileAgentTasks(1L, List.of(5L, 6L));
        verify(taskRepo).resetDroppedByAgent(1L, List.of(5L, 6L));
    }

    // ── resetOrphanedTasks() / cancelTimedOutTasks() ──────────────────

    @Test
    void resetOrphanedTasksDelegatesWithTheComputedCutoff() {
        when(taskRepo.resetOrphanedByOfflineAgents(any())).thenReturn(3);
        assertEquals(3, service.resetOrphanedTasks(15));
    }

    @Test
    void cancelTimedOutTasksSkipsRowsThatBecameTerminalBetweenLookupAndLoad() {
        AgentTask t = task(1L, 1L, 9L, "completed"); // already terminal by the time we re-check
        when(taskRepo.findTimedOutTaskIds()).thenReturn(List.of(1L));
        assertEquals(0, service.cancelTimedOutTasks());
        verify(scheduleRuns, never()).onTaskTerminal(any());
    }

    @Test
    void cancelTimedOutTasksCancelsAndCountsEligibleRows() {
        AgentTask t = task(1L, 1L, 9L, "running");
        t.setTimeoutMinutes(30);
        when(taskRepo.findTimedOutTaskIds()).thenReturn(List.of(1L));

        int n = service.cancelTimedOutTasks();

        assertEquals(1, n);
        assertEquals("cancelled", t.getStatus());
        assertTrue(t.getError().contains("30"));
        verify(scheduleRuns).onTaskTerminal(t);
    }

    // ── listAll() / getGlobal() ──────────────────────────────────────

    @Test
    void listAllFallsBackToCreatedAtForAnUnknownSortColumn() {
        when(taskRepo.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        service.listAll(null, null, null, "not-a-real-column", "ASC", 0, 20);
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(taskRepo).findAll(any(Specification.class), captor.capture());
        assertEquals("createdAt", captor.getValue().getSort().iterator().next().getProperty());
    }

    @Test
    void getGlobalResolvesNamesGracefullyWhenTheProjectIsMissing() {
        AgentTask t = task(1L, 999L, null, "pending");
        t.setPoolId(2L);
        when(projectRepo.findById(999L)).thenReturn(Optional.empty());
        var dto = service.getGlobal(1L);
        assertNull(dto.projectName());
    }
}
