package com.martecyber.ares.detections;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.dto.CreateDetectionRequest;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.detections.dto.EscalateBatchRequest;
import com.martecyber.ares.detections.dto.EscalateDetectionRequest;
import com.martecyber.ares.detections.dto.EscalationResultDto;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingScoreTypeRepository;
import com.martecyber.ares.findings.FindingStatus;
import com.martecyber.ares.findings.FindingStatusHistoryRepository;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.tags.Tag;
import com.martecyber.ares.tags.TagRepository;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link DetectionService} — filtering/scope resolution (staff vs.
 * client vs. org-restricted), CRUD, status-transition validation, escalate() (new/existing
 * finding), affected-assets replacement, tag assignment, and status-history recording. No Spring
 * context.
 *
 * <p>{@code countGroupedByAql}'s two "real execution" branches ({@link
 * com.martecyber.ares.aql.compile.AqlGroupCountSupport#execute} and the iteration-bucket
 * theta-join) build raw {@code jakarta.persistence.criteria} queries directly against the
 * injected {@code EntityManager} — genuine Criteria API usage, not something a plain
 * {@code mock(EntityManager.class)} can stand in for without reimplementing a fake JPA provider.
 * Those two branches are exercised only in integration, not here; this class covers every guard
 * clause in {@code countGroupedByAql} that runs before reaching them (empty org-scope
 * short-circuit, the iteration-bucket-without-projectId guard).
 */
class DetectionServiceTest {

    private DetectionRepository repo;
    private DetectionAffectedAssetRepository affectedRepo;
    private AffectionRepository affectionRepo;
    private FindingRepository findingRepo;
    private FindingScoreTypeRepository scoreTypeRepo;
    private FindingStatusRepository findingStatusRepo;
    private FindingStatusHistoryRepository findingStatusHistoryRepo;
    private AssetRepository assetRepo;
    private ReferenceCatalogRepository referenceCatalogRepo;
    private com.martecyber.ares.references.ReferenceService referenceService;
    private com.martecyber.ares.kb.cve.CveRepository cveRepo;
    private DetectionStatusHistoryRepository historyRepo;
    private UserRepository userRepo;
    private DetectionTagRepository tagRepo;
    private TagRepository tagCatalogRepo;
    private ProjectRepository projectRepo;
    private DetectionIterationStatsService iterationStatsService;
    private OrgScopeService orgScope;
    private DetectionAqlRegistry aqlRegistry;
    private DetectionStatusRepository statusRepo;
    private DetectionStatusTransitionRepository statusTransitionRepo;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private com.martecyber.ares.aql.compile.AqlVariableExpander aqlVariableExpander;
    private DetectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(DetectionRepository.class);
        affectedRepo = mock(DetectionAffectedAssetRepository.class);
        affectionRepo = mock(AffectionRepository.class);
        findingRepo = mock(FindingRepository.class);
        scoreTypeRepo = mock(FindingScoreTypeRepository.class);
        findingStatusRepo = mock(FindingStatusRepository.class);
        findingStatusHistoryRepo = mock(FindingStatusHistoryRepository.class);
        assetRepo = mock(AssetRepository.class);
        referenceCatalogRepo = mock(ReferenceCatalogRepository.class);
        referenceService = mock(com.martecyber.ares.references.ReferenceService.class);
        cveRepo = mock(com.martecyber.ares.kb.cve.CveRepository.class);
        historyRepo = mock(DetectionStatusHistoryRepository.class);
        userRepo = mock(UserRepository.class);
        tagRepo = mock(DetectionTagRepository.class);
        tagCatalogRepo = mock(TagRepository.class);
        projectRepo = mock(ProjectRepository.class);
        iterationStatsService = mock(DetectionIterationStatsService.class);
        orgScope = mock(OrgScopeService.class);
        aqlRegistry = mock(DetectionAqlRegistry.class);
        statusRepo = mock(DetectionStatusRepository.class);
        statusTransitionRepo = mock(DetectionStatusTransitionRepository.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        aqlVariableExpander = mock(com.martecyber.ares.aql.compile.AqlVariableExpander.class);

        service = new DetectionService(repo, affectedRepo, affectionRepo, findingRepo, scoreTypeRepo,
            findingStatusRepo, findingStatusHistoryRepo, assetRepo, referenceCatalogRepo, referenceService, cveRepo, historyRepo,
            userRepo, tagRepo, tagCatalogRepo, projectRepo, iterationStatsService, orgScope, aqlRegistry,
            statusRepo, statusTransitionRepo, workflowEventDispatcher, aqlVariableExpander);

        when(referenceCatalogRepo.findAll()).thenReturn(List.of());
        when(scoreTypeRepo.findAll()).thenReturn(List.of());
        when(findingStatusRepo.findAll()).thenReturn(List.of());
        when(tagRepo.findTagsForDetectionIds(anyList())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(affectionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(findingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        setAuth("1");
        mockPlatformAdmin(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication setAuth(String userId, String... authorities) {
        List<GrantedAuthority> auths = List.of(authorities).stream()
            .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        var auth = new UsernamePasswordAuthenticationToken(userId, null, auths);
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private void mockPlatformAdmin(boolean isAdmin) {
        when(orgScope.isPlatformAdmin(any())).thenReturn(isAdmin);
    }

    private Detection detection(Long id, Long projectId, String status) {
        Detection d = new Detection();
        ReflectionTestUtils.setField(d, "id", id);
        d.setProjectId(projectId);
        d.setSeverity("medium");
        d.setPriority((short) 2);
        d.setStatus(status);
        d.setStatusId(1L);
        d.setTitle("Detection " + id);
        d.setCreatedAt(OffsetDateTime.now());
        d.setUpdatedAt(OffsetDateTime.now());
        d.setLastSeen(OffsetDateTime.now());
        return d;
    }

    private DetectionStatus status(Long id, String name, boolean escalatable) {
        DetectionStatus s = new DetectionStatus();
        ReflectionTestUtils.setField(s, "id", id);
        s.setName(name);
        s.setEscalatable(escalatable);
        return s;
    }

    private Project project(Long id, boolean clientsCanView) {
        return project(id, clientsCanView, null);
    }

    private Project project(Long id, boolean clientsCanView, Long organizationId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setClientsCanViewDetections(clientsCanView);
        p.setOrganizationId(organizationId);
        return p;
    }

    private Asset asset(Long id, String code, String identifier) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setCode(code);
        a.setIdentifier(identifier);
        a.setType("host");
        return a;
    }

    // ── list() ───────────────────────────────────────────────────────────────

    @Test
    void listWithNoFiltersPassesPlaceholderListsAndCollapsesBlankQuery() {
        when(repo.filter(eq(10L), isNull(), eq(true), eq(List.of(-1L)), eq(true), eq(List.of("__none__")),
            eq(true), eq(List.of("__none__")), eq(true), eq(List.of("__none__")), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        Page<DetectionDto> result = service.list(10L, null, null, null, null, "   ", null, null, 0, 20);

        assertEquals(0, result.getTotalElements());
        verify(orgScope).assertProjectAccess(any(), eq(10L));
    }

    @Test
    void listWithFiltersPassesThemThroughAndLowercasesQuery() {
        when(repo.filter(eq(10L), isNull(), eq(false), eq(List.of(5L)), eq(false), eq(List.of("high")),
            eq(false), eq(List.of("new")), eq(false), eq(List.of("nessus")), eq("Login"), eq("%login%"), any()))
            .thenReturn(new PageImpl<>(List.of()));

        service.list(10L, List.of(5L), List.of("high"), List.of("new"), List.of("nessus"), "Login", null, null, 0, 20);

        verify(repo).filter(eq(10L), isNull(), eq(false), eq(List.of(5L)), eq(false), eq(List.of("high")),
            eq(false), eq(List.of("new")), eq(false), eq(List.of("nessus")), eq("Login"), eq("%login%"), any());
    }

    @Test
    void listPlatformAdminWithoutProjectIdIsUnrestricted() {
        when(repo.filter(isNull(), isNull(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, null, null, null, null, null, null, 0, 20);

        verify(repo).filter(isNull(), isNull(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void listNonAdminWithoutProjectIdRestrictsToAccessibleOrgs() {
        mockPlatformAdmin(false);
        when(orgScope.accessibleOrgIds(any())).thenReturn(Set.of(3L, 4L));
        when(repo.filter(isNull(), eq(Set.of(3L, 4L)), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, null, null, null, null, null, null, 0, 20);

        verify(repo).filter(isNull(), eq(Set.of(3L, 4L)), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void listNonAdminWithNoAccessibleOrgsShortCircuitsWithoutQuerying() {
        mockPlatformAdmin(false);
        when(orgScope.accessibleOrgIds(any())).thenReturn(Set.of());

        Page<DetectionDto> result = service.list(null, null, null, null, null, null, null, null, 0, 20);

        assertEquals(0, result.getTotalElements());
        verify(repo, never()).filter(any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void listClientUserWithoutProjectIdIsRejected() {
        setAuth("9", "ROLE_CLIENT_USER");

        assertThrows(AccessDeniedException.class,
            () -> service.list(null, null, null, null, null, null, null, null, 0, 20));
    }

    @Test
    void listClientUserIsRejectedWhenProjectDoesNotAllowClientVisibility() {
        setAuth("9", "ROLE_CLIENT_USER");
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, false)));

        assertThrows(AccessDeniedException.class,
            () -> service.list(10L, null, null, null, null, null, null, null, 0, 20));
    }

    @Test
    void listClientUserIsAllowedWhenProjectOptsIn() {
        setAuth("9", "ROLE_CLIENT_USER");
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, true)));
        when(repo.filter(eq(10L), isNull(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        Page<DetectionDto> result = service.list(10L, null, null, null, null, null, null, null, 0, 20);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void listMapsAssetAndTagsAndCatalogCodesIntoDto() {
        Detection d = detection(1L, 10L, "new");
        d.setAssetId(100L);
        when(repo.filter(eq(10L), isNull(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(d)));
        when(affectedRepo.findByDetectionIdIn(List.of(1L))).thenReturn(List.of(new DetectionAffectedAsset(1L, 100L)));
        when(assetRepo.findAllById(any())).thenReturn(List.of(asset(100L, "AST-1", "host.example.com")));
        when(tagRepo.findTagsForDetectionIds(List.of(1L))).thenReturn(
            List.of(tagRow(1L, 7L, "urgent", "#f00")));

        Page<DetectionDto> result = service.list(10L, null, null, null, null, null, null, null, 0, 20);

        DetectionDto dto = result.getContent().get(0);
        assertEquals("AST-1", dto.assetCode());
        assertEquals("host.example.com", dto.assetIdentifier());
        assertEquals(1, dto.affectedAssets().size());
        assertEquals(100L, dto.affectedAssets().get(0).id());
        assertEquals(1, dto.tags().size());
        assertEquals("urgent", dto.tags().get(0).name());
    }

    private DetectionTagRepository.DetectionTagRow tagRow(Long detId, Long id, String name, String color) {
        return new DetectionTagRepository.DetectionTagRow() {
            public Long getDetectionId() { return detId; }
            public Long getId() { return id; }
            public String getName() { return name; }
            public String getColor() { return color; }
        };
    }

    @Test
    void listSortsBySeverityMappingToPriorityColumn() {
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(repo.filter(any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), captor.capture())).thenReturn(new PageImpl<>(List.of()));

        service.list(10L, null, null, null, null, null, "severity", "asc", 0, 20);

        Pageable p = captor.getValue();
        assertEquals("priority", p.getSort().iterator().next().getProperty());
        assertTrue(p.getSort().iterator().next().isAscending());
    }

    @Test
    void listSortsByUnknownKeyFallingBackToCreatedAtDescending() {
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(repo.filter(any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
            anyBoolean(), any(), any(), any(), captor.capture())).thenReturn(new PageImpl<>(List.of()));

        service.list(10L, null, null, null, null, null, "bogus", null, 0, 20);

        Pageable p = captor.getValue();
        assertEquals("createdAt", p.getSort().iterator().next().getProperty());
        assertTrue(p.getSort().iterator().next().isDescending());
    }

    // ── listByAql / countByAql / countGroupedByAql ─────────────────────────────

    @Test
    void listByAqlTwoArgOverloadDelegatesWithNullOrganizationId() {
        when(aqlVariableExpander.expand("severity == critical", 10L, null)).thenReturn("severity == critical");
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        Page<DetectionDto> result = service.listByAql(10L, "severity == critical", null, null, 0, 20);

        assertEquals(0, result.getTotalElements());
        verify(orgScope).assertProjectAccess(any(), eq(10L));
    }

    @Test
    void listByAqlOrgWideVariantAssertsOrgAccessAndScopesToThatOrg() {
        when(aqlVariableExpander.expand(anyString(), isNull(), eq(5L))).thenReturn("severity == high");
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listByAql(null, 5L, "severity == high", null, null, 0, 20);

        verify(orgScope).assertOrgAccess(any(), eq(5L));
    }

    @Test
    void listByAqlWithNoAccessibleOrgsShortCircuitsWithoutParsing() {
        mockPlatformAdmin(false);
        when(orgScope.accessibleOrgIds(any())).thenReturn(Set.of());

        Page<DetectionDto> result = service.listByAql(null, "severity == high", null, null, 0, 20);

        assertEquals(0, result.getTotalElements());
        verifyNoInteractions(aqlVariableExpander);
        verify(repo, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
    }

    @Test
    void countByAqlDelegatesToRepoCount() {
        when(aqlVariableExpander.expand(anyString(), eq(10L), isNull())).thenReturn("severity == critical");
        when(repo.count(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(7L);

        long count = service.countByAql(10L, null, "severity == critical");

        assertEquals(7L, count);
    }

    @Test
    void countByAqlWithNoAccessibleOrgsReturnsZeroWithoutQuerying() {
        mockPlatformAdmin(false);
        when(orgScope.accessibleOrgIds(any())).thenReturn(Set.of());

        long count = service.countByAql(null, null, "severity == critical");

        assertEquals(0, count);
        verify(repo, never()).count(any(org.springframework.data.jpa.domain.Specification.class));
    }

    @Test
    void countGroupedByAqlWithNoAccessibleOrgsReturnsEmptyList() {
        mockPlatformAdmin(false);
        when(orgScope.accessibleOrgIds(any())).thenReturn(Set.of());

        var result = service.countGroupedByAql(null, null, "severity == high", "severity", null, null, null, null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(aqlVariableExpander);
    }

    @Test
    void countGroupedByAqlIterationBucketWithoutProjectIdThrows() {
        when(aqlVariableExpander.expand(anyString(), isNull(), isNull())).thenReturn("severity == high");

        assertThrows(com.martecyber.ares.aql.compile.AqlCompileException.class,
            () -> service.countGroupedByAql(null, null, "severity == high", "severity", "iteration", null, null, null));
    }

    // ── get() ────────────────────────────────────────────────────────────────

    @Test
    void getReturnsDtoAndChecksProjectAccess() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));

        DetectionDto dto = service.get(1L);

        assertEquals(1L, dto.id());
        verify(orgScope).assertProjectAccess(any(), eq(10L));
    }

    @Test
    void getThrowsNotFoundForMissingDetection() {
        when(repo.findByIdWithReferences(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.get(99L));
    }

    @Test
    void getIsRejectedForClientWhenProjectDoesNotAllowVisibility() {
        setAuth("9", "ROLE_CLIENT_USER");
        Detection d = detection(1L, 10L, "new");
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, false)));

        assertThrows(AccessDeniedException.class, () -> service.get(1L));
    }

    // ── listSources / listDetectionAssets ───────────────────────────────────────

    @Test
    void listSourcesChecksAccessAndDelegates() {
        when(repo.findDistinctSources(10L)).thenReturn(List.of("nessus", "caido"));

        var sources = service.listSources(10L);

        assertEquals(List.of("nessus", "caido"), sources);
        verify(orgScope).assertProjectAccess(any(), eq(10L));
    }

    @Test
    void listDetectionAssetsReturnsEmptyWhenNoneFound() {
        when(repo.findDistinctAssetIds(10L)).thenReturn(List.of());

        var assets = service.listDetectionAssets(10L);

        assertTrue(assets.isEmpty());
        verify(assetRepo, never()).findAllById(any());
    }

    @Test
    void listDetectionAssetsSortsByIdentifierWithNullsFirst() {
        when(repo.findDistinctAssetIds(10L)).thenReturn(List.of(1L, 2L));
        when(assetRepo.findAllById(List.of(1L, 2L))).thenReturn(List.of(
            asset(1L, "AST-1", "zeta.example.com"), asset(2L, "AST-2", "alpha.example.com")));

        var assets = service.listDetectionAssets(10L);

        assertEquals("alpha.example.com", assets.get(0).identifier());
        assertEquals("zeta.example.com", assets.get(1).identifier());
    }

    // ── assignTag / unassignTag ──────────────────────────────────────────────

    @Test
    void assignTagRejectsTagFromADifferentOrganization() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, false, 5L)));
        Tag tag = mock(Tag.class);
        when(tag.getOrganizationId()).thenReturn(99L);
        when(tagCatalogRepo.findById(7L)).thenReturn(Optional.of(tag));

        assertThrows(ResponseStatusException.class, () -> service.assignTag(1L, 7L));
    }

    @Test
    void assignTagAllowsAPlatformTagWithNoOrganization() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project(10L, false)));
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));
        Tag tag = mock(Tag.class);
        when(tag.getOrganizationId()).thenReturn(null);
        when(tagCatalogRepo.findById(7L)).thenReturn(Optional.of(tag));

        DetectionDto dto = service.assignTag(1L, 7L);

        assertEquals(1L, dto.id());
        verify(tagRepo).assign(1L, 7L);
    }

    @Test
    void unassignTagDelegatesAndReturnsRefreshedDto() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));

        DetectionDto dto = service.unassignTag(1L, 7L);

        assertEquals(1L, dto.id());
        verify(tagRepo).unassign(1L, 7L);
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Test
    void createResolvesDefaultStatusAndAddsFirstAffectedAsset() {
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        var req = new CreateDetectionRequest(10L, 100L, "high", "new", "Title", "Desc", null);

        DetectionDto dto = service.create(req);

        assertEquals("high", dto.severity());
        assertEquals("new", dto.status());
        verify(affectedRepo).save(argThat(a -> a.getAssetId().equals(100L)));
        verify(historyRepo).save(argThat(h -> "created".equals(h.getEventType())));
        verify(workflowEventDispatcher).onDetectionCreated(any());
    }

    @Test
    void createWithoutAssetIdDoesNotCreateAffectedAssetRow() {
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        var req = new CreateDetectionRequest(10L, null, "high", "new", "Title", null, null);

        service.create(req);

        verify(affectedRepo, never()).save(any());
    }

    @Test
    void createWithUnknownStatusThrows() {
        when(statusRepo.findByName("bogus")).thenReturn(Optional.empty());
        var req = new CreateDetectionRequest(10L, null, "high", "bogus", "Title", null, null);

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
    }

    // ── ingestExternal() ─────────────────────────────────────────────────────

    @Test
    void ingestExternalCreatesNewDetectionOnFirstSighting() {
        when(repo.findFirstByProjectIdAndSourceTypeAndSourceTemplateId(10L, "caido", "tmpl-1"))
            .thenReturn(Optional.empty());
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));

        var result = service.ingestExternal(10L, "caido", "tmpl-1", 100L, "high", "Title", "Desc", "{}");

        assertTrue(result.created());
        assertEquals("caido", result.detection().getSourceType());
        verify(workflowEventDispatcher).onDetectionCreated(any());
    }

    @Test
    void ingestExternalDefaultsSeverityToInfoWhenNotProvided() {
        when(repo.findFirstByProjectIdAndSourceTypeAndSourceTemplateId(10L, "caido", "tmpl-1"))
            .thenReturn(Optional.empty());
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));

        var result = service.ingestExternal(10L, "caido", "tmpl-1", null, null, "Title", "Desc", "{}");

        assertEquals("info", result.detection().getSeverity());
    }

    @Test
    void ingestExternalBumpsOccurrenceOnRepeatSighting() {
        Detection existing = detection(1L, 10L, "new");
        existing.setSourceType("caido");
        existing.setSourceTemplateId("tmpl-1");
        existing.setOccurrenceCount(3);
        when(repo.findFirstByProjectIdAndSourceTypeAndSourceTemplateId(10L, "caido", "tmpl-1"))
            .thenReturn(Optional.of(existing));

        var result = service.ingestExternal(10L, "caido", "tmpl-1", 100L, "high", "Title", "Desc", "{}");

        assertFalse(result.created());
        assertEquals(4, result.detection().getOccurrenceCount());
        verify(workflowEventDispatcher, never()).onDetectionCreated(any());
        verify(historyRepo).save(argThat(h -> "reseen".equals(h.getEventType())));
    }

    // ── replaceAffectedAssets() ──────────────────────────────────────────────

    @Test
    void replaceAffectedAssetsReplacesTheWholeSet() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));
        when(assetRepo.findAllById(List.of(100L, 200L))).thenReturn(
            List.of(asset(100L, "AST-1", "a"), asset(200L, "AST-2", "b")));

        service.replaceAffectedAssets(1L, List.of(100L, 200L, 100L));

        verify(affectedRepo).deleteByDetectionId(1L);
        verify(affectedRepo, times(2)).save(any());
    }

    @Test
    void replaceAffectedAssetsWithEmptyListClearsWithoutSavingAny() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));

        service.replaceAffectedAssets(1L, List.of());

        verify(affectedRepo).deleteByDetectionId(1L);
        verify(affectedRepo, never()).save(any());
    }

    @Test
    void replaceAffectedAssetsRejectsUnknownAssetIds() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(assetRepo.findAllById(List.of(100L))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.replaceAffectedAssets(1L, List.of(100L)));
    }

    // ── updateStatus() ───────────────────────────────────────────────────────

    @Test
    void updateStatusAppliesAValidTransitionAndRecordsHistory() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("confirmed")).thenReturn(Optional.of(status(2L, "confirmed", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(2L));

        DetectionDto dto = service.updateStatus(1L, "confirmed", "looks real");

        assertEquals("confirmed", dto.status());
        verify(historyRepo).save(argThat(h -> "status_changed".equals(h.getEventType())
            && "new".equals(h.getFromStatus()) && "looks real".equals(h.getNote())));
        verify(workflowEventDispatcher).onDetectionUpdated(any());
    }

    @Test
    void updateStatusRejectsATransitionNotInTheAllowedSet() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("archived")).thenReturn(Optional.of(status(3L, "archived", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(2L));

        assertThrows(IllegalStateException.class, () -> service.updateStatus(1L, "archived", null));
    }

    @Test
    void updateStatusBlankNoteIsStoredAsNull() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(repo.findByIdWithReferences(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("confirmed")).thenReturn(Optional.of(status(2L, "confirmed", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(2L));

        service.updateStatus(1L, "confirmed", "   ");

        verify(historyRepo).save(argThat(h -> h.getNote() == null));
    }

    // ── escalate() ───────────────────────────────────────────────────────────

    @Test
    void escalateRejectsANonEscalatableStatus() {
        Detection d = detection(1L, 10L, "confirmed");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("confirmed")).thenReturn(Optional.of(status(2L, "confirmed", false)));
        var req = new EscalateDetectionRequest("new_finding", null, "T", null, 10L, null);

        assertThrows(IllegalStateException.class, () -> service.escalate(1L, req));
    }

    @Test
    void escalateNewFindingRequiresProjectId() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        var req = new EscalateDetectionRequest("new_finding", null, "T", null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.escalate(1L, req));
    }

    @Test
    void escalateExistingFindingRequiresFindingId() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        var req = new EscalateDetectionRequest("existing_finding", null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.escalate(1L, req));
    }

    @Test
    void escalateRejectsAnUnknownMode() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        var req = new EscalateDetectionRequest("bogus", null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.escalate(1L, req));
    }

    @Test
    void escalateNewFindingCreatesFindingAndAffectionAndMovesDetectionToAffected() {
        Detection d = detection(1L, 10L, "new");
        d.setAssetId(100L);
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("affected")).thenReturn(Optional.of(status(4L, "affected", false)));
        when(findingStatusRepo.findByName("open")).thenReturn(Optional.of(findingStatus(1L, "open")));
        when(findingRepo.countByProjectId(10L)).thenReturn(0L);
        when(assetRepo.findById(200L)).thenReturn(Optional.of(asset(200L, "AST-2", "b")));

        var req = new EscalateDetectionRequest("new_finding", null, "Escalated title", "critical", 10L, 200L);
        DetectionDto dto = service.escalate(1L, req);

        assertEquals("affected", dto.status());
        verify(findingRepo).save(argThat(f -> "Escalated title".equals(f.getTitle()) && "critical".equals(f.getSeverity())));
        verify(affectionRepo, times(2)).save(any());
        verify(findingStatusHistoryRepo).save(any());
    }

    @Test
    void escalateExistingFindingLinksToThatFindingWithoutCreatingOne() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("affected")).thenReturn(Optional.of(status(4L, "affected", false)));
        Finding existing = findingWithCode(55L, "ENG-10-1");
        when(findingRepo.findById(55L)).thenReturn(Optional.of(existing));

        var req = new EscalateDetectionRequest("existing_finding", 55L, null, null, null, null);
        DetectionDto dto = service.escalate(1L, req);

        assertEquals("affected", dto.status());
        verify(findingRepo, never()).save(any());
    }

    private FindingStatus findingStatus(Long id, String name) {
        FindingStatus s = new FindingStatus();
        ReflectionTestUtils.setField(s, "id", id);
        s.setName(name);
        return s;
    }

    private Finding findingWithCode(Long id, String code) {
        Finding f = new Finding();
        ReflectionTestUtils.setField(f, "id", id);
        f.setCode(code);
        f.setTitle("Existing finding");
        f.setSeverity("high");
        return f;
    }

    // ── escalateBatch() ────────────────────────────────────────────────────────

    @Test
    void escalateBatchNewFindingNewAffectionMovesAllDetectionsToAffected() {
        Detection d1 = detection(1L, 10L, "new");
        Detection d2 = detection(2L, 10L, "reopened");
        when(repo.findById(1L)).thenReturn(Optional.of(d1));
        when(repo.findById(2L)).thenReturn(Optional.of(d2));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("reopened")).thenReturn(Optional.of(status(2L, "reopened", true)));
        when(statusRepo.findByName("affected")).thenReturn(Optional.of(status(4L, "affected", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(4L));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(2L)).thenReturn(List.of(4L));
        when(findingStatusRepo.findByName("open")).thenReturn(Optional.of(findingStatus(1L, "open")));
        when(findingRepo.countByProjectId(10L)).thenReturn(0L);
        when(affectionRepo.countByFindingId(any())).thenReturn(0L);
        // Override the passthrough save() stubs from setUp() — a mock save() never runs real
        // @GeneratedValue identity assignment, so simulate it to exercise the id-returning path.
        when(findingRepo.save(any())).thenAnswer(inv -> {
            Finding f = inv.getArgument(0);
            if (f.getId() == null) ReflectionTestUtils.setField(f, "id", 500L);
            return f;
        });
        when(affectionRepo.save(any())).thenAnswer(inv -> {
            Affection a = inv.getArgument(0);
            if (a.getId() == null) ReflectionTestUtils.setField(a, "id", 900L);
            return a;
        });

        var req = new EscalateBatchRequest(List.of(1L, 2L), "new_finding", null, "Combined title", "critical",
            10L, "new_affection", null, "Aff title", "Aff desc");
        EscalationResultDto result = service.escalateBatch(req);

        assertEquals(2, result.detectionCount());
        assertNotNull(result.findingId());
        assertNotNull(result.affectionId());
        verify(findingRepo).save(argThat(f -> "Combined title".equals(f.getTitle()) && "critical".equals(f.getSeverity())));
        verify(affectionRepo, times(2)).save(any());
        assertEquals("affected", d1.getStatus());
        assertEquals("affected", d2.getStatus());
        verify(historyRepo, times(2)).save(argThat(h -> "affected".equals(h.getToStatus())));
    }

    @Test
    void escalateBatchExistingFindingExistingAffectionLinksDetectionsWithoutCreatingEither() {
        Detection d1 = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d1));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("affected")).thenReturn(Optional.of(status(4L, "affected", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(4L));
        Finding existingFinding = findingWithCode(55L, "ENG-10-1");
        when(findingRepo.findById(55L)).thenReturn(Optional.of(existingFinding));
        Affection existingAffection = new Affection();
        ReflectionTestUtils.setField(existingAffection, "id", 900L);
        existingAffection.setFindingId(55L);
        when(affectionRepo.findById(900L)).thenReturn(Optional.of(existingAffection));

        var req = new EscalateBatchRequest(List.of(1L), "existing_finding", 55L, null, null,
            null, "existing_affection", 900L, null, null);
        EscalationResultDto result = service.escalateBatch(req);

        assertEquals(55L, result.findingId());
        assertEquals(900L, result.affectionId());
        verify(findingRepo, never()).save(any());
        assertEquals("affected", d1.getStatus());
    }

    @Test
    void escalateBatchRejectsWhenAnyDetectionCannotTransitionToAffected() {
        Detection eligible = detection(1L, 10L, "new");
        Detection ineligible = detection(2L, 10L, "fixed");
        when(repo.findById(1L)).thenReturn(Optional.of(eligible));
        when(repo.findById(2L)).thenReturn(Optional.of(ineligible));
        when(statusRepo.findByName("new")).thenReturn(Optional.of(status(1L, "new", true)));
        when(statusRepo.findByName("fixed")).thenReturn(Optional.of(status(3L, "fixed", false)));
        when(statusRepo.findByName("affected")).thenReturn(Optional.of(status(4L, "affected", false)));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(1L)).thenReturn(List.of(4L));
        when(statusTransitionRepo.findToStatusIdsByFromStatusId(3L)).thenReturn(List.of()); // fixed -> affected not allowed

        var req = new EscalateBatchRequest(List.of(1L, 2L), "existing_finding", 55L, null, null,
            null, "existing_affection", 900L, null, null);

        assertThrows(IllegalStateException.class, () -> service.escalateBatch(req));
        verify(findingRepo, never()).findById(any());
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheDetectionAndDispatchesTheEvent() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));

        service.delete(1L);

        verify(repo).deleteById(1L);
        verify(workflowEventDispatcher).onDetectionDeleted(1L, 10L);
    }

    @Test
    void deleteThrowsNotFoundForMissingDetection() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.delete(99L));
    }

    // ── getAffections() / getHistory() ──────────────────────────────────────

    @Test
    void getAffectionsHandlesAMissingFindingGracefully() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        Affection affection = new Affection();
        ReflectionTestUtils.setField(affection, "id", 5L);
        affection.setFindingId(999L);
        affection.setCode("AFF-1");
        when(affectionRepo.findByDetectionId(1L)).thenReturn(List.of(affection));
        when(findingRepo.findById(999L)).thenReturn(Optional.empty());

        var result = service.getAffections(1L);

        assertEquals(1, result.size());
        assertNull(result.get(0).findingId());
    }

    @Test
    void getHistoryReturnsOrderedEntries() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        var h1 = new DetectionStatusHistory(1L, "created", null, "new", null, null, null, OffsetDateTime.now());
        when(historyRepo.findByDetectionIdOrderByChangedAtDesc(eq(1L), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(h1)));

        var result = service.getHistory(1L, 0, 20);

        assertEquals(1, result.items().size());
        assertEquals("created", result.items().get(0).eventType());
    }

    @Test
    void getHistoryClampsPageAndSizeBeforeQuerying() {
        Detection d = detection(1L, 10L, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(d));
        when(historyRepo.findByDetectionIdOrderByChangedAtDesc(eq(1L), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        service.getHistory(1L, -3, 999);

        verify(historyRepo).findByDetectionIdOrderByChangedAtDesc(eq(1L), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
    }
}
