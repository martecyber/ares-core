package com.martecyber.ares.projects;

import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRelationshipService;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.imports.AssetImportHelper;
import com.martecyber.ares.projects.dto.AddScopeEntryRequest;
import com.martecyber.ares.projects.dto.CreateProjectRequest;
import com.martecyber.ares.projects.dto.ProjectDto;
import com.martecyber.ares.users.OrgScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MSSP_OPERATOR is org-restricted, so every project endpoint it can reach must check the
 * project's organization before reading or writing anything.
 */
class ProjectControllerAccessTest {

    private ProjectService service;
    private ProjectAssetAccessService assetAccessSvc;
    private ScopeEntryAssetDeriver deriver;
    private AssetScopeClassifier classifier;
    private ScopeClassifyScheduler scheduler;
    private MonitorStatsService monitorStats;
    private AssetRelationshipService relSvc;
    private OrgScopeService orgScope;
    private ProjectController controller;
    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        service = mock(ProjectService.class);
        assetAccessSvc = mock(ProjectAssetAccessService.class);
        deriver = mock(ScopeEntryAssetDeriver.class);
        classifier = mock(AssetScopeClassifier.class);
        scheduler = mock(ScopeClassifyScheduler.class);
        monitorStats = mock(MonitorStatsService.class);
        relSvc = mock(AssetRelationshipService.class);
        orgScope = mock(OrgScopeService.class);
        controller = new ProjectController(service, assetAccessSvc, deriver, classifier, scheduler,
            mock(AssetImportHelper.class), mock(AssetRepository.class), mock(ProjectRepository.class),
            monitorStats, relSvc, orgScope);
        doThrow(new AccessDeniedException("no")).when(orgScope).assertProjectAccess(any(), any());
        doThrow(new AccessDeniedException("no")).when(orgScope).assertOrgAccess(any(), any());
    }

    @Test
    void everyOperatorReachableProjectEndpointIsOrgScoped() {
        List<Executable> calls = List.of(
            () -> controller.complete(1L),
            () -> controller.approveIteration(1L),
            () -> controller.addScope(1L, mock(AddScopeEntryRequest.class)),
            () -> controller.removeScope(1L, 2L),
            () -> controller.deriveAssetsFromScope(1L),
            () -> controller.getMyRole(1L),
            () -> controller.listAssets(1L),
            () -> controller.addAsset(1L, 2L),
            () -> controller.removeAsset(1L, 2L),
            () -> controller.hideRelationship(1L, 2L, 3L, "domain_a"),
            () -> controller.addAssetManually(1L, new ProjectController.ManualAssetRequest("domain", "example.com", null, null, null, null)),
            () -> controller.classifyScope(1L),
            () -> controller.classifyScopeStatus(1L),
            () -> controller.getScopeStatuses(1L),
            () -> controller.setOverride(1L, 2L, Map.of("status", AssetScopeClassifier.IN_SCOPE)),
            () -> controller.clearOverride(1L, 2L),
            () -> controller.explainScope(1L, 2L),
            () -> controller.listIterations(1L),
            () -> controller.monitorStats(1L));
        for (Executable call : calls) assertThrows(AccessDeniedException.class, call);
        verifyNoInteractions(service, assetAccessSvc, deriver, classifier, scheduler, monitorStats, relSvc);
    }

    @Test
    void createAndPreviewCodeAreOrgScoped() {
        assertThrows(AccessDeniedException.class, () -> controller.create(mock(CreateProjectRequest.class), auth));
        assertThrows(AccessDeniedException.class, () -> controller.previewCode(5L, null, auth));
        verifyNoInteractions(service);
    }

    @Test
    void scheduleOnlyShowsAccessibleOrganizations() {
        ProjectDto mine = mock(ProjectDto.class);
        ProjectDto foreign = mock(ProjectDto.class);
        when(mine.organizationId()).thenReturn(10L);
        when(foreign.organizationId()).thenReturn(20L);
        when(service.schedule(any(), any())).thenReturn(List.of(mine, foreign));
        when(orgScope.accessibleOrgIds(auth)).thenReturn(Set.of(10L));

        assertEquals(List.of(mine), controller.schedule("2026-01-01", "2026-12-31", auth));

        when(orgScope.accessibleOrgIds(auth)).thenReturn(null); // platform admin
        assertEquals(2, controller.schedule("2026-01-01", "2026-12-31", auth).size());
    }

    @Test
    void projectNeverLinksAnotherOrganizationsAsset() {
        ProjectAssetAccessRepository repo = mock(ProjectAssetAccessRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        ProjectAssetAccessService svc = new ProjectAssetAccessService(repo, projects, assets,
            mock(AssetScopeClassifier.class), mock(ScopeClassifyScheduler.class));

        Project project = new Project();
        project.setOrganizationId(10L);
        Asset foreign = new Asset();
        ReflectionTestUtils.setField(foreign, "id", 2L);
        foreign.setOrganizationId(20L);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(assets.findById(2L)).thenReturn(Optional.of(foreign));

        assertThrows(IllegalArgumentException.class, () -> svc.add(1L, 2L));
        verify(repo, never()).save(any());
    }
}
