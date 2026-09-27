package com.martecyber.ares.imports;

import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetToolSightingService;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.assets.ServiceVisibilityRepository;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionAffectedAssetRepository;
import com.martecyber.ares.detections.DetectionHttpSampleService;
import com.martecyber.ares.detections.DetectionIterationStatsService;
import com.martecyber.ares.detections.DetectionReferenceExtractor;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionScoreRepository;
import com.martecyber.ares.detections.DetectionStatus;
import com.martecyber.ares.detections.DetectionStatusHistoryRepository;
import com.martecyber.ares.detections.DetectionStatusRepository;
import com.martecyber.ares.detections.DetectionUrlReferenceExtractor;
import com.martecyber.ares.findings.FindingScoreType;
import com.martecyber.ares.findings.FindingScoreTypeRepository;
import com.martecyber.ares.plugins.Plugin;
import com.martecyber.ares.plugins.PluginRepository;
import com.martecyber.ares.plugins.MissingPluginException;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectScopeEntryRepository;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link ImportService} — the file-upload {@code runImport} pipeline
 * and its API-driven twin {@code processParseResult}: asset dedup/resolution, link resolution
 * (including the exclusive-vs-idempotent link distinction between the two entry points),
 * service-visibility recording, the WEB_APPLICATION→WEB_ENDPOINT bootstrap, the final
 * service→interface repair pass, detection upsert (dedup-hash lookup, tool-driven status
 * transitions, HTTP-sample/score side effects), and the outer success/failure bookkeeping
 * (scan_import status, scope re-classification scheduling). No Spring context.
 *
 * <p>{@code parser} is a single Mockito mock standing in for every {@link ImportParser} in the
 * {@code toolId="nmap", formatId="default"} slot — {@code findParser}'s matching logic itself is
 * plain stream filtering over real strings, so one parser identity is enough to exercise both the
 * "found" and "not found" branches.
 */
class ImportServiceTest {

    private static final String VISIBILITY_STATE_KEY = "__visibilityState"; // mirrors ImportService's private key

    private ScanImportRepository importRepo;
    private ProjectRepository projectRepo;
    private DetectionRepository detectionRepo;
    private DetectionAffectedAssetRepository detectionAffectedRepo;
    private AssetImportHelper assetHelper;
    private ProjectAssetAccessRepository projectAssetRepo;
    private ServiceVisibilityRepository visibilityRepo;
    private AssetToolSightingService sightingService;
    private ImportParser parser;
    private AssetScopeClassifier classifier;
    private ScopeClassifyScheduler scheduler;
    private ProjectScopeEntryRepository scopeRepo;
    private DetectionReferenceExtractor referenceExtractor;
    private DetectionUrlReferenceExtractor urlReferenceExtractor;
    private DetectionStatusHistoryRepository historyRepo;
    private ScanImportChangeRepository changeRepo;
    private ScanImportRecorder scanImportRecorder;
    private DetectionHttpSampleService detectionHttpSampleService;
    private DetectionIterationStatsService iterationStatsService;
    private DetectionStatusRepository detectionStatusRepo;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private FindingScoreTypeRepository scoreTypeRepo;
    private DetectionScoreRepository detectionScoreRepo;
    private PluginRepository pluginRepo;
    private AsyncImportRunner asyncImportRunner;
    private ImportService service;

    private ParseResult parseResult;
    private final Map<String, Long> assetIdSeed = new HashMap<>();
    private final AtomicLong scanImportIdSeq = new AtomicLong(500);
    private final AtomicLong detectionIdSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() throws Exception {
        importRepo = mock(ScanImportRepository.class);
        projectRepo = mock(ProjectRepository.class);
        detectionRepo = mock(DetectionRepository.class);
        detectionAffectedRepo = mock(DetectionAffectedAssetRepository.class);
        assetHelper = mock(AssetImportHelper.class);
        projectAssetRepo = mock(ProjectAssetAccessRepository.class);
        visibilityRepo = mock(ServiceVisibilityRepository.class);
        sightingService = mock(AssetToolSightingService.class);
        classifier = mock(AssetScopeClassifier.class);
        scheduler = mock(ScopeClassifyScheduler.class);
        scopeRepo = mock(ProjectScopeEntryRepository.class);
        referenceExtractor = mock(DetectionReferenceExtractor.class);
        urlReferenceExtractor = mock(DetectionUrlReferenceExtractor.class);
        historyRepo = mock(DetectionStatusHistoryRepository.class);
        changeRepo = mock(ScanImportChangeRepository.class);
        scanImportRecorder = mock(ScanImportRecorder.class);
        detectionHttpSampleService = mock(DetectionHttpSampleService.class);
        iterationStatsService = mock(DetectionIterationStatsService.class);
        detectionStatusRepo = mock(DetectionStatusRepository.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        scoreTypeRepo = mock(FindingScoreTypeRepository.class);
        detectionScoreRepo = mock(DetectionScoreRepository.class);
        pluginRepo = mock(PluginRepository.class);
        when(pluginRepo.findByPluginId(anyString())).thenReturn(Optional.empty());
        asyncImportRunner = mock(AsyncImportRunner.class);

        parser = mock(ImportParser.class);
        when(parser.getToolId()).thenReturn("nmap");
        when(parser.getFormatId()).thenReturn("default");
        when(parser.getDisplayName()).thenReturn("Nmap");
        when(parser.getSupportedExtensions()).thenReturn(new String[]{".xml"});
        when(parser.validate(any())).thenReturn(true);
        parseResult = new ParseResult();
        when(parser.parse(any(byte[].class), any(ParseContext.class))).thenAnswer(inv -> parseResult);

        service = new ImportService(importRepo, projectRepo, detectionRepo, detectionAffectedRepo, assetHelper,
            projectAssetRepo, visibilityRepo, sightingService, List.of(parser), classifier, scheduler, scopeRepo,
            referenceExtractor, urlReferenceExtractor, historyRepo, changeRepo, scanImportRecorder,
            detectionHttpSampleService, iterationStatsService, detectionStatusRepo, workflowEventDispatcher,
            scoreTypeRepo, detectionScoreRepo, pluginRepo, asyncImportRunner);

        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, 5L)));
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(10L)).thenReturn(List.of());
        when(detectionStatusRepo.findAll()).thenReturn(List.of(
            detectionStatus(1L, "new"), detectionStatus(2L, "reopened"), detectionStatus(3L, "solved")));
        when(importRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(changeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(detectionAffectedRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(detectionScoreRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(detectionRepo.findByProjectIdAndDedupHash(anyLong(), anyString())).thenReturn(Optional.empty());
        when(scanImportRecorder.commit(any(ScanImport.class))).thenAnswer(inv -> {
            ScanImport s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", scanImportIdSeq.incrementAndGet());
            return s;
        });
        when(detectionRepo.save(any(Detection.class))).thenAnswer(inv -> {
            Detection d = inv.getArgument(0);
            if (d.getId() == null) ReflectionTestUtils.setField(d, "id", detectionIdSeq.incrementAndGet());
            return d;
        });
        when(assetHelper.resolveOrCreate(anyLong(), any(ParsedAsset.class))).thenAnswer(inv -> {
            ParsedAsset pa = inv.getArgument(1);
            return assetIdSeed.get(pa.getIdentifier() + "|" + pa.getType());
        });
    }

    private Project project(Long id, Long orgId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setOrganizationId(orgId);
        return p;
    }

    private DetectionStatus detectionStatus(Long id, String name) {
        DetectionStatus s = new DetectionStatus();
        ReflectionTestUtils.setField(s, "id", id);
        s.setName(name);
        return s;
    }

    private void stubAsset(String identifier, String type, Long id) {
        assetIdSeed.put(identifier + "|" + type, id);
    }

    private Detection detection(Long id, Long projectId, Long assetId, String status, int occurrenceCount) {
        Detection d = new Detection();
        ReflectionTestUtils.setField(d, "id", id);
        d.setProjectId(projectId);
        d.setAssetId(assetId);
        d.setSeverity("medium");
        d.setPriority((short) 2);
        d.setStatus(status);
        d.setStatusId(1L);
        d.setTitle("Existing");
        d.setOccurrenceCount(occurrenceCount);
        d.setCreatedAt(java.time.OffsetDateTime.now());
        d.setUpdatedAt(java.time.OffsetDateTime.now());
        return d;
    }

    private static final byte[] CONTENT = "dummy scan output".getBytes();

    // ── listForProject / listAvailableTools ─────────────────────────────────

    @Test
    void listForProjectMapsHasChangesFlag() {
        ScanImport s = new ScanImport();
        ReflectionTestUtils.setField(s, "id", 1L);
        s.setProjectId(10L);
        s.setTool("nmap");
        s.setFormat("default");
        s.setStatus("completed");
        s.setCreatedAt(java.time.OffsetDateTime.now());
        when(importRepo.findByProjectIdOrderByCreatedAtDesc(eq(10L), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(s)));
        when(changeRepo.existsByScanImportIdAndRevertedAtIsNull(1L)).thenReturn(true);

        var result = service.listForProject(10L, 0, 20);

        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).hasRollbackableChanges());
    }

    @Test
    void listAvailableToolsMapsParserMetadata() {
        var tools = service.listAvailableTools();

        assertEquals(1, tools.size());
        assertEquals("nmap", tools.get(0).get("toolId"));
        assertEquals("Nmap", tools.get(0).get("displayName"));
    }

    // ── runImport: guard clauses ─────────────────────────────────────────────

    @Test
    void runImportThrowsWhenProjectNotFound() {
        when(projectRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> service.runImport(99L, "nmap", "default", "f.xml", CONTENT, null, null, false));
        verifyNoInteractions(scanImportRecorder);
    }

    @Test
    void runImportThrowsWhenNoParserMatches() {
        assertThrows(IllegalArgumentException.class,
            () -> service.runImport(10L, "unknown-tool", "default", "f.xml", CONTENT, null, null, false));
        verifyNoInteractions(scanImportRecorder);
    }

    @Test
    void runImportShortOverloadDelegatesThroughEveryDefaultToTheFullSignature() {
        var result = service.runImport(10L, "nmap", "default", "f.xml", null);

        assertTrue(result.isSuccess());
        assertEquals("Import completed: 0 assets, 0 new detections, 0 updated", result.getMessage());
    }

    // ── runImport: empty content / invalid content ──────────────────────────

    @Test
    void runImportWithEmptyContentReturnsZeroResultSuccessWithoutParsing() {
        // The empty-content branch returns directly from inside the try block, before the
        // scheduler.schedule() call that normally follows try/catch/finally for every other path.
        var result = service.runImport(10L, "nmap", "default", "f.xml", new byte[0], null, null, false);

        assertTrue(result.isSuccess());
        assertEquals("Import completed: 0 assets, 0 new detections, 0 updated", result.getMessage());
        verifyNoInteractions(scopeRepo);
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void runImportWithInvalidContentMarksImportFailed() {
        when(parser.validate(CONTENT)).thenReturn(false);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
        verify(scheduler, never()).schedule(any());
    }

    // ── runImport: asset resolution ─────────────────────────────────────────

    @Test
    void runImportHappyPathCreatesAssetDetectionAndDispatchesEvent() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        parseResult.addDetection(new ParsedDetection("SQLi", "high", "desc", "1.2.3.4", "tmpl-1", "{}"));
        stubAsset("1.2.3.4", AssetType.IP, 100L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getAssetsCreated());
        assertEquals(1, result.getDetectionsCreated());
        verify(workflowEventDispatcher).onDetectionCreated(any());
        verify(detectionAffectedRepo).save(argThat(a -> a.getAssetId().equals(100L)));
        verify(scheduler).schedule(10L);
    }

    @Test
    void runImportSkipsAssetOnResolveFailureAndAddsWarning() {
        parseResult.addAsset(new ParsedAsset("bad-host", AssetType.IP));
        when(assetHelper.resolveOrCreate(eq(5L), argThat(pa -> "bad-host".equals(pa.getIdentifier()))))
            .thenThrow(new RuntimeException("constraint violation"));

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getAssetsCreated());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("bad-host"));
    }

    @Test
    void runImportDedupesAssetsByIdentifierAndType() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        stubAsset("1.2.3.4", AssetType.IP, 100L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals(1, result.getAssetsCreated());
        verify(assetHelper, times(1)).resolveOrCreate(eq(5L), any());
    }

    @Test
    void runImportKeepsTwoAssetsSharingAnIdentifierButDifferingInType() {
        parseResult.addAsset(new ParsedAsset("shared.example.com", AssetType.HOST));
        parseResult.addAsset(new ParsedAsset("shared.example.com", AssetType.DOMAIN));
        stubAsset("shared.example.com", AssetType.HOST, 100L);
        stubAsset("shared.example.com", AssetType.DOMAIN, 200L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals(2, result.getAssetsCreated());
        verify(assetHelper, times(2)).resolveOrCreate(eq(5L), any());
    }

    // ── runImport: service visibility ───────────────────────────────────────

    @Test
    void runImportRecordsServiceVisibilityWhenSourceIpProvided() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(VISIBILITY_STATE_KEY, "open");
        parseResult.addAsset(new ParsedAsset("1.2.3.4:443/tcp", AssetType.SERVICE, meta));
        stubAsset("1.2.3.4:443/tcp", AssetType.SERVICE, 100L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, "9.9.9.9", "corp-nac", false);

        assertEquals(1, result.getVisibilityRecorded());
        verify(visibilityRepo).upsert(eq(100L), eq("9.9.9.9"), eq("corp-nac"), eq("OPEN"), anyLong());
        // VISIBILITY_STATE_KEY must be stripped before the asset is persisted
        verify(assetHelper).resolveOrCreate(eq(5L), argThat(pa -> !pa.getMetadata().containsKey(VISIBILITY_STATE_KEY)));
    }

    @Test
    void runImportSkipsVisibilityWhenNoSourceIpProvided() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4:443/tcp", AssetType.SERVICE));
        stubAsset("1.2.3.4:443/tcp", AssetType.SERVICE, 100L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals(0, result.getVisibilityRecorded());
        verifyNoInteractions(visibilityRepo);
    }

    @Test
    void runImportAddsWarningWhenVisibilityUpsertThrows() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4:443/tcp", AssetType.SERVICE));
        stubAsset("1.2.3.4:443/tcp", AssetType.SERVICE, 100L);
        when(visibilityRepo.upsert(eq(100L), any(), any(), any(), anyLong()))
            .thenThrow(new RuntimeException("db down"));

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, "9.9.9.9", null, false);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getVisibilityRecorded());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("1.2.3.4:443/tcp")));
    }

    // ── runImport: project-asset linking ─────────────────────────────────────

    @Test
    void runImportAddsWarningWhenProjectAssetLinkingThrows() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        stubAsset("1.2.3.4", AssetType.IP, 100L);
        doThrow(new RuntimeException("fk violation")).when(projectAssetRepo).linkIfAbsent(10L, 100L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("100")));
    }

    // ── runImport: link processing ──────────────────────────────────────────

    @Test
    void runImportLinksResolvedAssetPairViaLinkType() {
        parseResult.addAsset(new ParsedAsset("iface-1", AssetType.INTERFACE));
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        parseResult.addLink("iface-1", "1.2.3.4", AssetLinkType.INTERFACE_IP);
        stubAsset("iface-1", AssetType.INTERFACE, 100L);
        stubAsset("1.2.3.4", AssetType.IP, 200L);

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(assetHelper).linkIfAbsent(100L, 200L, AssetLinkType.INTERFACE_IP);
    }

    @Test
    void runImportSkipsLinkWhenAnEndpointDoesNotResolve() {
        parseResult.addLink("nowhere-1", "nowhere-2", AssetLinkType.INTERFACE_IP);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        verify(assetHelper, never()).linkIfAbsent(any(), any(), any());
    }

    @Test
    void runImportLogsAndContinuesWhenLinkingThrows() {
        parseResult.addAsset(new ParsedAsset("iface-1", AssetType.INTERFACE));
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        parseResult.addLink("iface-1", "1.2.3.4", AssetLinkType.INTERFACE_IP);
        stubAsset("iface-1", AssetType.INTERFACE, 100L);
        stubAsset("1.2.3.4", AssetType.IP, 200L);
        doThrow(new RuntimeException("bad link")).when(assetHelper).linkIfAbsent(100L, 200L, AssetLinkType.INTERFACE_IP);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("iface-1")));
    }

    @Test
    void runImportLinksServiceToInterfaceInFinalPass() {
        parseResult.addAsset(new ParsedAsset("iface-10.0.0.5", AssetType.INTERFACE));
        parseResult.addAsset(new ParsedAsset("10.0.0.5:443/tcp", AssetType.SERVICE));
        stubAsset("iface-10.0.0.5", AssetType.INTERFACE, 100L);
        stubAsset("10.0.0.5:443/tcp", AssetType.SERVICE, 200L);

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(assetHelper).linkIfAbsent(100L, 200L, "interface_service");
    }

    @Test
    void runImportSkipsFinalPassLinkWhenInterfaceAssetIsNotInThisImport() {
        parseResult.addAsset(new ParsedAsset("10.0.0.5:443/tcp", AssetType.SERVICE));
        stubAsset("10.0.0.5:443/tcp", AssetType.SERVICE, 200L);

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(assetHelper, never()).linkIfAbsent(any(), eq(200L), eq("interface_service"));
    }

    // ── runImport: ensureWebEndpoints ───────────────────────────────────────

    @Test
    void runImportEnsuresWebEndpointForWebApplicationAndRelocatesHttpProbeMetadata() {
        Map<String, Object> webAppMeta = new HashMap<>();
        webAppMeta.put("title", "Login Page");
        webAppMeta.put("server", "nginx");
        webAppMeta.put("statusCode", 200);
        parseResult.addAsset(new ParsedAsset("https://app.example.com", AssetType.WEB_APPLICATION, webAppMeta));
        stubAsset("https://app.example.com", AssetType.WEB_APPLICATION, 300L);
        stubAsset("https://app.example.com", AssetType.WEB_ENDPOINT, 400L);

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
        verify(assetHelper).linkIfAbsent(300L, 400L, AssetLinkType.WEBAPP_ENDPOINT);
        verify(projectAssetRepo).linkIfAbsent(10L, 400L);
        verify(assetHelper).stripWebAppHttpProbeFields(300L);
    }

    @Test
    void runImportEnsureWebEndpointsLogsAndContinuesOnFailure() {
        parseResult.addAsset(new ParsedAsset("https://app.example.com", AssetType.WEB_APPLICATION));
        stubAsset("https://app.example.com", AssetType.WEB_APPLICATION, 300L);
        // No stub for the WEB_ENDPOINT identifier+type -> resolveOrCreate returns null,
        // which AssetImportHelper never does in practice, but linkIfAbsent(300L, null, ...)
        // throwing an NPE-style failure exercises the same defensive catch block either way.
        doThrow(new RuntimeException("bad endpoint")).when(assetHelper)
            .linkIfAbsent(eq(300L), any(), eq(AssetLinkType.WEBAPP_ENDPOINT));

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertTrue(result.isSuccess());
    }

    // ── runImport: new detection ─────────────────────────────────────────────

    @Test
    void runImportNewDetectionFromFixedStateStartsAsFixed() {
        parseResult.addDetection(new ParsedDetection("Fixed issue", "high", "desc", null, "tmpl-1", "{}", "fixed"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionRepo).save(argThat(d -> "fixed".equals(d.getStatus())));
    }

    @Test
    void runImportNewDetectionFromOpenStateStartsAsNew() {
        parseResult.addDetection(new ParsedDetection("Open issue", "high", "desc", null, "tmpl-1", "{}", "open"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionRepo).save(argThat(d -> "new".equals(d.getStatus())));
    }

    @Test
    void runImportNewDetectionTruncatesAnOverlongTitle() {
        String longTitle = "x".repeat(400);
        parseResult.addDetection(new ParsedDetection(longTitle, "high", "desc", null, "tmpl-1", "{}"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionRepo).save(argThat(d -> d.getTitle().length() == 300));
    }

    @Test
    void runImportNewDetectionStripsNulBytesFromDescription() {
        parseResult.addDetection(new ParsedDetection("T", "high", "bad desc", null, "tmpl-1", "{}"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionRepo).save(argThat(d -> "baddesc".equals(d.getDescription())));
    }

    @Test
    void runImportNewDetectionCreatesHttpSampleWhenCaptured() {
        ParsedDetection pd = new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}");
        pd.setRequestContent("GET / HTTP/1.1");
        pd.setResponseContent("HTTP/1.1 200 OK");
        parseResult.addDetection(pd);

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionHttpSampleService).create(anyLong(), any());
    }

    @Test
    void runImportNewDetectionSkipsHttpSampleWhenNothingCaptured() {
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verifyNoInteractions(detectionHttpSampleService);
    }

    @Test
    void runImportNewDetectionCreatesScoreWhenTypeMatches() {
        ParsedDetection pd = new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}");
        pd.setCvssScore(new java.math.BigDecimal("7.5"));
        pd.setCvssVersion("CVSS 3.1");
        pd.setCvssVector("AV:N/AC:L");
        parseResult.addDetection(pd);
        FindingScoreType type = new FindingScoreType();
        ReflectionTestUtils.setField(type, "id", 9L);
        when(scoreTypeRepo.findByTitle("CVSS 3.1")).thenReturn(Optional.of(type));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionScoreRepo).save(argThat(s -> s.getTypeId().equals(9L)
            && s.getScore().compareTo(new java.math.BigDecimal("7.5")) == 0));
    }

    @Test
    void runImportNewDetectionSkipsScoreWhenScoreTypeUnknown() {
        ParsedDetection pd = new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}");
        pd.setCvssScore(new java.math.BigDecimal("7.5"));
        pd.setCvssVersion("CVSS 9.9");
        parseResult.addDetection(pd);
        when(scoreTypeRepo.findByTitle("CVSS 9.9")).thenReturn(Optional.empty());

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verifyNoInteractions(detectionScoreRepo);
    }

    @Test
    void runImportNewDetectionUsesAffectsIdentifierOverDetectedAtAsset() {
        parseResult.addAsset(new ParsedAsset("endpoint-1", AssetType.WEB_ENDPOINT));
        parseResult.addAsset(new ParsedAsset("webapp-1", AssetType.WEB_APPLICATION));
        stubAsset("endpoint-1", AssetType.WEB_ENDPOINT, 100L);
        stubAsset("webapp-1", AssetType.WEB_APPLICATION, 200L);
        ParsedDetection pd = new ParsedDetection("T", "high", "d", "endpoint-1", "tmpl-1", "{}");
        pd.setAffectsIdentifier("webapp-1");
        parseResult.addDetection(pd);

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionAffectedRepo).save(argThat(a -> a.getAssetId().equals(200L)));
    }

    @Test
    void runImportNewDetectionFallsBackToDetectedAtAssetWhenNoAffectsGiven() {
        parseResult.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        stubAsset("1.2.3.4", AssetType.IP, 100L);
        parseResult.addDetection(new ParsedDetection("T", "high", "d", "1.2.3.4", "tmpl-1", "{}"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verify(detectionAffectedRepo).save(argThat(a -> a.getAssetId().equals(100L)));
    }

    @Test
    void runImportNewDetectionWithNoResolvableAssetSkipsAffectedAssetRow() {
        parseResult.addDetection(new ParsedDetection("Project-level finding", "high", "d", null, "tmpl-1", "{}"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        verifyNoInteractions(detectionAffectedRepo);
    }

    // ── runImport: existing detection (status transitions) ──────────────────

    @Test
    void runImportExistingDetectionFixedTransitionsFromNewToFixed() {
        Detection existing = detection(1L, 10L, null, "new", 1);
        when(detectionRepo.findByProjectIdAndDedupHash(eq(10L), anyString())).thenReturn(Optional.of(existing));
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}", "fixed"));

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals(1, result.getDetectionsUpdated());
        assertEquals("fixed", existing.getStatus());
        assertEquals(2, existing.getOccurrenceCount());
        verify(workflowEventDispatcher, never()).onDetectionCreated(any());
    }

    @Test
    void runImportExistingDetectionActiveStateReopensAFixedOne() {
        Detection existing = detection(1L, 10L, null, "fixed", 3);
        when(detectionRepo.findByProjectIdAndDedupHash(eq(10L), anyString())).thenReturn(Optional.of(existing));
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}", "open"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals("reopened", existing.getStatus());
    }

    @Test
    void runImportExistingDetectionUserClosedStatusIsNeverOverridden() {
        Detection existing = detection(1L, 10L, null, "not_affected", 1);
        when(detectionRepo.findByProjectIdAndDedupHash(eq(10L), anyString())).thenReturn(Optional.of(existing));
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}", "fixed"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals("not_affected", existing.getStatus());
    }

    @Test
    void runImportExistingDetectionWithNoStatusChangeIsRecordedAsReseen() {
        Detection existing = detection(1L, 10L, null, "new", 1);
        when(detectionRepo.findByProjectIdAndDedupHash(eq(10L), anyString())).thenReturn(Optional.of(existing));
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}", "open"));

        service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertEquals("new", existing.getStatus());
        verify(historyRepo).save(argThat(h -> "reseen".equals(h.getEventType())));
    }

    // ── runImport: outer success/failure bookkeeping ─────────────────────────

    @Test
    void runImportUnexpectedExceptionMarksImportFailedAndSkipsScheduling() {
        parseResult.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}"));
        when(detectionRepo.save(any(Detection.class))).thenThrow(new RuntimeException("db exploded"));

        var result = service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false);

        assertFalse(result.isSuccess());
        assertEquals("db exploded", result.getMessage());
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void runImportSchedulerExceptionAfterSuccessIsSwallowed() {
        doThrow(new RuntimeException("scheduler down")).when(scheduler).schedule(10L);

        var result = assertDoesNotThrow(() ->
            service.runImport(10L, "nmap", "default", "f.xml", CONTENT, null, null, false));

        assertTrue(result.isSuccess());
    }

    // ── processParseResult ───────────────────────────────────────────────────

    @Test
    void processParseResultThrowsWhenProjectNotFound() {
        ParseResult pr = new ParseResult();
        assertThrows(IllegalArgumentException.class, () -> service.processParseResult(99L, 5L, "tenable", pr));
    }

    @Test
    void processParseResultHappyPathCreatesAssetsAndDetections() {
        ParseResult pr = new ParseResult();
        pr.addAsset(new ParsedAsset("1.2.3.4", AssetType.IP));
        pr.addDetection(new ParsedDetection("SQLi", "high", "d", "1.2.3.4", "tmpl-1", "{}"));
        stubAsset("1.2.3.4", AssetType.IP, 100L);

        var result = service.processParseResult(10L, 5L, "tenable", pr);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getAssetsCreated());
        assertEquals(1, result.getDetectionsCreated());
        verify(workflowEventDispatcher).onDetectionCreated(any());
        verify(scheduler).schedule(10L);
    }

    @Test
    void processParseResultUsesExclusiveLinkForHostInterfaceAndInterfaceIp() {
        ParseResult pr = new ParseResult();
        pr.addAsset(new ParsedAsset("host-1", AssetType.HOST));
        pr.addAsset(new ParsedAsset("iface-1", AssetType.INTERFACE));
        pr.addLink("host-1", "iface-1", AssetLinkType.HOST_INTERFACE);
        stubAsset("host-1", AssetType.HOST, 100L);
        stubAsset("iface-1", AssetType.INTERFACE, 200L);

        service.processParseResult(10L, 5L, "tenable", pr);

        verify(assetHelper).linkExclusive(100L, 200L, AssetLinkType.HOST_INTERFACE);
        verify(assetHelper, never()).linkIfAbsent(100L, 200L, AssetLinkType.HOST_INTERFACE);
    }

    @Test
    void processParseResultUsesLinkIfAbsentForNonExclusiveLinkTypes() {
        ParseResult pr = new ParseResult();
        pr.addAsset(new ParsedAsset("iface-1", AssetType.INTERFACE));
        pr.addAsset(new ParsedAsset("svc-1", AssetType.SERVICE));
        pr.addLink("iface-1", "svc-1", AssetLinkType.INTERFACE_SERVICE);
        stubAsset("iface-1", AssetType.INTERFACE, 100L);
        stubAsset("svc-1", AssetType.SERVICE, 200L);

        service.processParseResult(10L, 5L, "tenable", pr);

        verify(assetHelper).linkIfAbsent(100L, 200L, AssetLinkType.INTERFACE_SERVICE);
        verify(assetHelper, never()).linkExclusive(any(), any(), any());
    }

    @Test
    void processParseResultMarksFailedOnUnexpectedException() {
        ParseResult pr = new ParseResult();
        pr.addDetection(new ParsedDetection("T", "high", "d", null, "tmpl-1", "{}"));
        when(detectionRepo.save(any(Detection.class))).thenThrow(new RuntimeException("boom"));

        var result = service.processParseResult(10L, 5L, "tenable", pr);

        assertFalse(result.isSuccess());
        assertEquals("boom", result.getMessage());
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void processParseResultFallsBackToProjectsOwnOrgIdWhenOrgIdArgIsNull() {
        ParseResult pr = new ParseResult();

        service.processParseResult(10L, null, "tenable", pr);

        verify(importRepo).save(argThat(s -> Long.valueOf(5L).equals(s.getOrganizationId())));
    }

    @Test
    void listForProjectClampsPageAndSizeBeforeQuerying() {
        when(importRepo.findByProjectIdOrderByCreatedAtDesc(eq(10L), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        service.listForProject(10L, -7, 999);

        verify(importRepo).findByProjectIdOrderByCreatedAtDesc(eq(10L), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
    }

    @Test
    void runImportRejectsATrulyUnknownToolWithAGenericError() {
        // pluginRepo already stubbed (setUp) to return empty for any id — no Plugin row at all.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.runImport(10L, "bogus-tool", "default", "f.txt", new byte[0]));
        assertTrue(ex.getMessage().contains("bogus-tool"));
    }

    @Test
    void runImportReportsAMissingPluginWhenTheToolsOwnPluginIsInstalledButNoMatchingParserIsRegistered() {
        Plugin trivyPlugin = new Plugin();
        trivyPlugin.setPluginId("trivy");
        trivyPlugin.setDisplayName("Trivy");
        trivyPlugin.setEnabled(true);
        when(pluginRepo.findByPluginId("trivy")).thenReturn(Optional.of(trivyPlugin));

        var ex = assertThrows(MissingPluginException.class,
            () -> service.runImport(10L, "trivy", "default", "f.json", new byte[0]));
        assertEquals("trivy", ex.getPluginId());
    }

    @Test
    void startImportReturnsARunningRecordImmediatelyAndDispatchesToTheAsyncRunner() {
        ScanImport record = service.startImport(10L, "nmap", "default", "f.xml", new byte[]{1, 2, 3},
            null, null, false, null);

        assertNotNull(record.getId());
        assertEquals("running", record.getStatus());
        // The actual parsing/persistence must NOT have run inline — only the async runner was
        // handed the work (mocked as a no-op here; AsyncImportRunnerTest covers what it does).
        verify(asyncImportRunner).processAsync(any(), eq(record), eq(parser), eq("nmap"),
            any(), isNull(), isNull(), eq(false), isNull());
        verifyNoInteractions(detectionRepo);
    }

    @Test
    void startImportStillValidatesSynchronouslyForAnUnknownTool() {
        // Same fast-fail as runImport — a bad tool id must never even reach the async runner.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.startImport(10L, "bogus-tool", "default", "f.txt", new byte[0],
                null, null, false, null));
        assertTrue(ex.getMessage().contains("bogus-tool"));
        verifyNoInteractions(asyncImportRunner);
    }
}
