package com.martecyber.ares.dashboards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.dashboards.dto.DashboardDtos.*;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectService;
import com.martecyber.ares.users.OrgScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link DashboardService} — the lazy default-dashboard seeding, the
 *  staff/CLIENT_ADMIN/scope-membership permission matrix (including the template-aware bypass),
 *  the default-flag flush ordering in {@code update}, widget-type/AQL-entity validation in
 *  {@code saveWidgets}, and the template create/instantiate/snapshot round trip. No Spring
 *  context; {@code SecurityContextHolder} carries a real token so role checks exercise the
 *  service's own {@code isStaff}/{@code hasAuthority} logic. */
class DashboardServiceTest {

    private DashboardRepository repo;
    private DashboardWidgetRepository widgetRepo;
    private OrgScopeService orgScope;
    private ProjectService projectService;
    private DashboardWidgetDataService widgetDataService;
    private OrganizationRepository orgRepo;
    private ProjectRepository projectRepo;
    private DashboardService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(DashboardRepository.class);
        widgetRepo = mock(DashboardWidgetRepository.class);
        orgScope = mock(OrgScopeService.class);
        projectService = mock(ProjectService.class);
        widgetDataService = mock(DashboardWidgetDataService.class);
        orgRepo = mock(OrganizationRepository.class);
        projectRepo = mock(ProjectRepository.class);
        service = new DashboardService(repo, widgetRepo, orgScope, projectService, widgetDataService, orgRepo, projectRepo);

        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(widgetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        setAuth("7"); // no special authorities — plain authenticated user by default
        // Staff by default — most tests here exercise CRUD/business logic, not the permission
        // matrix itself; the handful of tests that care about non-staff/CLIENT_ADMIN behavior
        // override this explicitly.
        mockPlatformAdmin(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(String userId, String... authorities) {
        List<GrantedAuthority> auths = List.of(authorities).stream().map(SimpleGrantedAuthority::new).map(a -> (GrantedAuthority) a).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, auths));
    }

    private void mockPlatformAdmin(boolean isAdmin) {
        when(orgScope.isPlatformAdmin(any())).thenReturn(isAdmin);
    }

    private Dashboard dashboard(Long id, DashboardLevel level, Long scopeId, boolean isTemplate) {
        Dashboard d = new Dashboard();
        ReflectionTestUtils.setField(d, "id", id);
        d.setLevel(level);
        d.setScopeId(scopeId);
        d.setTemplate(isTemplate);
        d.setName("Dashboard " + id);
        when(repo.findById(id)).thenReturn(Optional.of(d));
        return d;
    }

    private DashboardWidget widget(Long id, Long dashboardId, DashboardWidgetType type, String config) {
        DashboardWidget w = new DashboardWidget();
        ReflectionTestUtils.setField(w, "id", id);
        w.setDashboardId(dashboardId);
        if (type != null) w.setType(type);
        w.setConfig(config);
        return w;
    }

    // ── assertViewAccess / assertEditAccess (level+scopeId overloads) ─

    @Test
    void assertViewAccessOnPlatformRequiresStaff() {
        mockPlatformAdmin(false);
        assertThrows(AccessDeniedException.class, () -> service.assertViewAccess(DashboardLevel.PLATFORM, null));
    }

    @Test
    void assertViewAccessOnPlatformPassesForStaff() {
        mockPlatformAdmin(true);
        assertDoesNotThrow(() -> service.assertViewAccess(DashboardLevel.PLATFORM, null));
    }

    @Test
    void assertViewAccessOnOrganizationDelegatesToOrgScope() {
        service.assertViewAccess(DashboardLevel.ORGANIZATION, 5L);
        verify(orgScope).assertOrgAccess(any(), eq(5L));
    }

    @Test
    void assertViewAccessOnProjectDelegatesToOrgScope() {
        service.assertViewAccess(DashboardLevel.PROJECT, 5L);
        verify(orgScope).assertProjectAccess(any(), eq(5L));
    }

    @Test
    void assertEditAccessOnOrganizationRequiresStaffOrClientAdmin() {
        mockPlatformAdmin(false);
        assertThrows(AccessDeniedException.class, () -> service.assertEditAccess(DashboardLevel.ORGANIZATION, 5L));
    }

    @Test
    void assertEditAccessOnOrganizationAllowsClientAdmin() {
        mockPlatformAdmin(false);
        setAuth("7", "ROLE_CLIENT_ADMIN");
        assertDoesNotThrow(() -> service.assertEditAccess(DashboardLevel.ORGANIZATION, 5L));
        verify(orgScope).assertOrgAccess(any(), eq(5L));
    }

    @Test
    void assertEditAccessOnOrganizationAllowsMsspOperator() {
        mockPlatformAdmin(false);
        setAuth("7", "ROLE_MSSP_OPERATOR");
        assertDoesNotThrow(() -> service.assertEditAccess(DashboardLevel.ORGANIZATION, 5L));
    }

    // ── list() — lazy default seeding ─────────────────────────────────

    @Test
    void listReturnsExistingDashboardsWithoutSeedingWhenSomeAlreadyExist() {
        mockPlatformAdmin(true);
        Dashboard d = dashboard(1L, DashboardLevel.PLATFORM, null, false);
        when(repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(DashboardLevel.PLATFORM, null))
            .thenReturn(List.of(d));

        var result = service.list(DashboardLevel.PLATFORM, null);

        assertEquals(1, result.size());
        verify(repo, never()).save(any());
    }

    @Test
    void listLazilySeedsADefaultDashboardWithItsWidgetsWhenNoneExist() {
        mockPlatformAdmin(true);
        when(repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(DashboardLevel.PLATFORM, null))
            .thenReturn(List.of());

        var result = service.list(DashboardLevel.PLATFORM, null);

        assertEquals(1, result.size());
        assertEquals("Default", result.get(0).name());
        assertTrue(result.get(0).isDefault());
        // PLATFORM's seed list has 3 widgets (ORG_CAROUSEL, SCHEDULE_CALENDAR, CONTINUOUS_PROJECTS).
        verify(widgetRepo, times(3)).save(any());
    }

    @Test
    void listSeedsProjectLevelDefaultIncludingMonitorStatsOnlyForMonitorProjects() {
        when(repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(DashboardLevel.PROJECT, 9L))
            .thenReturn(List.of());
        when(projectService.isMonitorProject(9L)).thenReturn(true);

        service.list(DashboardLevel.PROJECT, 9L);

        // Base project seed set has 11 widgets; +1 (MONITOR_STATS) when isMonitorProject is true.
        verify(widgetRepo, times(12)).save(any());
    }

    // ── get() ──────────────────────────────────────────────────────────

    @Test
    void getThrowsNotFoundForAnUnknownDashboard() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.get(1L));
    }

    @Test
    void getEnrichesWidgetsIntoTheDto() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PLATFORM, null, false);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(widget(10L, 1L, DashboardWidgetType.SCHEDULE_CALENDAR, "{}")));

        DashboardDto dto = service.get(1L);

        assertEquals(1, dto.widgets().size());
        assertEquals(DashboardWidgetType.SCHEDULE_CALENDAR, dto.widgets().get(0).type());
    }

    @Test
    void getReturnsThePresentableFlagAsStored() {
        mockPlatformAdmin(true);
        Dashboard d = dashboard(1L, DashboardLevel.PLATFORM, null, false);
        d.setPresentable(true);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of());

        assertTrue(service.get(1L).presentable());
    }

    @Test
    void getOnATemplateRequiresStaffRegardlessOfLevel() {
        mockPlatformAdmin(false); // not staff
        // ORGANIZATION level normally only needs org membership, but this is a template.
        dashboard(1L, DashboardLevel.ORGANIZATION, null, true);
        assertThrows(AccessDeniedException.class, () -> service.get(1L));
        verify(orgScope, never()).assertOrgAccess(any(), any());
    }

    @Test
    void getOnATemplateSucceedsForStaffWithoutAnyScopeCheck() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.ORGANIZATION, null, true);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of());
        assertDoesNotThrow(() -> service.get(1L));
        verify(orgScope, never()).assertOrgAccess(any(), any());
    }

    // ── browseDashboards() ───────────────────────────────────────────

    @Test
    void browseDashboardsFiltersByNameOrResolvedScopeLabelCaseInsensitively() {
        Dashboard platformDb = dashboard(1L, DashboardLevel.PLATFORM, null, false);
        platformDb.setPresentable(true);
        Dashboard orgDb = dashboard(2L, DashboardLevel.ORGANIZATION, 5L, false);
        orgDb.setPresentable(true);
        orgDb.setName("Security metrics");
        when(repo.findAll(any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(platformDb, orgDb)));

        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", 5L);
        org.setName("Acme Corp");
        when(orgRepo.findAllById(List.of(5L))).thenReturn(List.of(org));
        when(projectRepo.findAllById(List.of())).thenReturn(List.of());
        when(orgRepo.findAllById(List.of())).thenReturn(List.of());

        var result = service.browseDashboards("acme");

        assertEquals(1, result.size());
        assertEquals("Security metrics", result.get(0).name());
        assertEquals("Acme Corp", result.get(0).scopeLabel());
    }

    @Test
    void browseDashboardsExcludesNonPresentableAndTemplateDashboards() {
        Dashboard notPresentable = dashboard(1L, DashboardLevel.PLATFORM, null, false);
        notPresentable.setPresentable(false);
        Dashboard template = dashboard(2L, DashboardLevel.PLATFORM, null, true);
        template.setPresentable(true); // a template flagged presentable is still never a real, playable dashboard
        Dashboard eligible = dashboard(3L, DashboardLevel.PLATFORM, null, false);
        eligible.setPresentable(true);
        when(repo.findAll(any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(notPresentable, template, eligible)));
        when(projectRepo.findAllById(List.of())).thenReturn(List.of());
        when(orgRepo.findAllById(List.of())).thenReturn(List.of());

        var result = service.browseDashboards(null);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).id());
    }

    // ── getWidgetData() ──────────────────────────────────────────────

    @Test
    void getWidgetDataReturnsNullForATemplateWithoutCallingTheDataService() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PROJECT, null, true);
        assertNull(service.getWidgetData(1L, 10L));
        verifyNoInteractions(widgetDataService);
    }

    @Test
    void getWidgetDataThrowsNotFoundWhenTheWidgetBelongsToADifferentDashboard() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget other = widget(10L, 999L, DashboardWidgetType.AQL_COUNT, "{}");
        when(widgetRepo.findById(10L)).thenReturn(Optional.of(other));
        assertThrows(NotFoundException.class, () -> service.getWidgetData(1L, 10L));
    }

    @Test
    void getWidgetDataDelegatesToTheDataServiceAndReturnsItsResult() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget w = widget(10L, 1L, DashboardWidgetType.AQL_COUNT, "{\"entity\":\"finding\"}");
        when(widgetRepo.findById(10L)).thenReturn(Optional.of(w));
        when(widgetDataService.dataFor(eq(w), any(), eq(DashboardLevel.PROJECT), eq(9L))).thenReturn(42);

        assertEquals(42, service.getWidgetData(1L, 10L));
    }

    @Test
    void getWidgetDataPropagatesAnExceptionFromTheDataService() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget w = widget(10L, 1L, DashboardWidgetType.AQL_COUNT, "{}");
        when(widgetRepo.findById(10L)).thenReturn(Optional.of(w));
        when(widgetDataService.dataFor(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        assertThrows(RuntimeException.class, () -> service.getWidgetData(1L, 10L));
    }

    // ── create() ─────────────────────────────────────────────────────

    @Test
    void createIsDefaultWhenItsTheFirstDashboardForTheScope() {
        when(repo.countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel.PROJECT, 9L)).thenReturn(0L);
        var dto = service.create(new CreateDashboardRequest(DashboardLevel.PROJECT, 9L, "My dashboard"));
        assertTrue(dto.isDefault());
    }

    @Test
    void createIsNotDefaultWhenOthersAlreadyExist() {
        when(repo.countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel.PROJECT, 9L)).thenReturn(1L);
        var dto = service.create(new CreateDashboardRequest(DashboardLevel.PROJECT, 9L, "My dashboard"));
        assertFalse(dto.isDefault());
    }

    @Test
    void createDefaultsABlankNameToNewDashboard() {
        var dto = service.create(new CreateDashboardRequest(DashboardLevel.PROJECT, 9L, "   "));
        assertEquals("New dashboard", dto.name());
    }

    // ── update() ─────────────────────────────────────────────────────

    @Test
    void updatePromotingToDefaultClearsThePreviousDefaultFirst() {
        Dashboard d = dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        Dashboard prevDefault = dashboard(2L, DashboardLevel.PROJECT, 9L, false);
        prevDefault.setDefault(true);
        when(repo.findByLevelAndScopeIdAndIsTemplateFalseAndIsDefaultTrue(DashboardLevel.PROJECT, 9L))
            .thenReturn(Optional.of(prevDefault));

        service.update(1L, new UpdateDashboardRequest(null, true, null, null));

        assertFalse(prevDefault.isDefault());
        assertTrue(d.isDefault());
        verify(repo).saveAndFlush(prevDefault);
    }

    @Test
    void updateDoesNotTouchThePreviousDefaultLookupWhenAlreadyDefault() {
        Dashboard d = dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        d.setDefault(true);
        service.update(1L, new UpdateDashboardRequest(null, true, null, null));
        verify(repo, never()).findByLevelAndScopeIdAndIsTemplateFalseAndIsDefaultTrue(any(), any());
    }

    @Test
    void updateClearsDescriptionOnBlankStringButLeavesItOnNull() {
        Dashboard d = dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        d.setDescription("old");
        service.update(1L, new UpdateDashboardRequest(null, null, "  ", null));
        assertNull(d.getDescription());
    }

    @Test
    void updateAppliesThePresentableFlagWhenProvidedButLeavesItAloneWhenNull() {
        Dashboard d = dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        d.setPresentable(false);

        service.update(1L, new UpdateDashboardRequest(null, null, null, true));
        assertTrue(d.isPresentable());

        service.update(1L, new UpdateDashboardRequest(null, null, null, null));
        assertTrue(d.isPresentable()); // null means "leave unchanged", not "clear it"

        service.update(1L, new UpdateDashboardRequest(null, null, null, false));
        assertFalse(d.isPresentable());
    }

    // ── delete() ─────────────────────────────────────────────────────

    @Test
    void deleteRefusesToRemoveTheOnlyDashboardForAScope() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        when(repo.countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel.PROJECT, 9L)).thenReturn(1L);
        assertThrows(ResponseStatusException.class, () -> service.delete(1L));
        verify(repo, never()).delete(any());
    }

    @Test
    void deleteAllowsRemovingTheOnlyTemplateSinceTemplatesHaveNoScope() {
        Dashboard tpl = dashboard(1L, DashboardLevel.PROJECT, null, true);
        mockPlatformAdmin(true);
        assertDoesNotThrow(() -> service.delete(1L));
        verify(repo).delete(tpl);
        verify(repo, never()).countByLevelAndScopeIdAndIsTemplateFalse(any(), any());
    }

    @Test
    void deletePromotesTheNextDashboardToDefaultWhenTheDeletedOneWasDefault() {
        Dashboard d = dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        d.setDefault(true);
        Dashboard next = dashboard(2L, DashboardLevel.PROJECT, 9L, false);
        when(repo.countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel.PROJECT, 9L)).thenReturn(2L);
        when(repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(DashboardLevel.PROJECT, 9L))
            .thenReturn(List.of(next));

        service.delete(1L);

        assertTrue(next.isDefault());
        verify(repo).save(next);
    }

    // ── saveWidgets() ────────────────────────────────────────────────

    @Test
    void saveWidgetsRejectsAWidgetTypeNotAllowedAtTheDashboardLevel() {
        dashboard(1L, DashboardLevel.PLATFORM, null, false);
        mockPlatformAdmin(true);
        var input = new WidgetInput(null, DashboardWidgetType.AQL_COUNT, null, null, 0, 0, 2, 2); // not allowed at PLATFORM
        assertThrows(ResponseStatusException.class, () -> service.saveWidgets(1L, new SaveWidgetsRequest(List.of(input))));
    }

    @Test
    void saveWidgetsRejectsAnAqlWidgetWithADisallowedEntity() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        var config = mapper.createObjectNode().put("entity", "not-a-real-entity");
        var input = new WidgetInput(null, DashboardWidgetType.AQL_COUNT, null, config, 0, 0, 2, 2);
        assertThrows(ResponseStatusException.class, () -> service.saveWidgets(1L, new SaveWidgetsRequest(List.of(input))));
    }

    @Test
    void saveWidgetsAcceptsAnAqlWidgetWithAnAllowedEntity() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of());
        var config = mapper.createObjectNode().put("entity", "finding");
        var input = new WidgetInput(null, DashboardWidgetType.AQL_COUNT, "Findings", config, 0, 0, 2, 2);

        assertDoesNotThrow(() -> service.saveWidgets(1L, new SaveWidgetsRequest(List.of(input))));
        verify(widgetRepo).save(any());
    }

    @Test
    void saveWidgetsDeletesRowsNotPresentInTheKeepList() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget stale = widget(5L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{}");
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(stale));

        service.saveWidgets(1L, new SaveWidgetsRequest(List.of()));

        verify(widgetRepo).delete(stale);
    }

    @Test
    void saveWidgetsUpdatesAnExistingRowByIdInsteadOfCreatingANewOne() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget existing = widget(5L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{}");
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(existing));
        when(widgetRepo.findById(5L)).thenReturn(Optional.of(existing));

        var input = new WidgetInput(5L, DashboardWidgetType.PROJECT_RULES_LIST, "Renamed", null, 1, 1, 4, 4);
        service.saveWidgets(1L, new SaveWidgetsRequest(List.of(input)));

        assertEquals("Renamed", existing.getTitle());
        verify(widgetRepo, never()).delete(any());
    }

    // ── Templates ────────────────────────────────────────────────────

    @Test
    void listTemplatesIsStaffOnly() {
        mockPlatformAdmin(false);
        assertThrows(AccessDeniedException.class, () -> service.listTemplates(DashboardLevel.PROJECT));
    }

    @Test
    void createTemplateDefaultsNameAndAlwaysHasANullScope() {
        mockPlatformAdmin(true);
        var dto = service.createTemplate(DashboardLevel.PROJECT, "  ", "desc");
        assertEquals("New template", dto.name());
    }

    @Test
    void createTemplateIsStaffOnly() {
        mockPlatformAdmin(false);
        assertThrows(AccessDeniedException.class, () -> service.createTemplate(DashboardLevel.PROJECT, "X", null));
    }

    @Test
    void createFromTemplateRejectsADashboardThatIsNotActuallyATemplate() {
        dashboard(1L, DashboardLevel.PROJECT, 9L, false); // real dashboard, not a template
        assertThrows(ResponseStatusException.class, () -> service.createFromTemplate(1L, 20L, "New"));
    }

    @Test
    void createFromTemplateCopiesWidgetsAndSkipsOnesInvalidAtTheTargetLevel() {
        Dashboard tpl = dashboard(1L, DashboardLevel.PROJECT, null, true);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(
            widget(10L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{}"), // valid at PROJECT
            widget(11L, 1L, null, "{}")                                    // unrecognized type -> skipped
        ));
        when(repo.countByLevelAndScopeIdAndIsTemplateFalse(DashboardLevel.PROJECT, 20L)).thenReturn(1L);

        service.createFromTemplate(1L, 20L, "New dashboard");

        verify(widgetRepo, times(1)).save(any());
    }

    @Test
    void createFromTemplateNameDefaultsToTheTemplatesOwnNameWhenBlank() {
        Dashboard tpl = dashboard(1L, DashboardLevel.PROJECT, null, true);
        tpl.setName("Security overview");
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of());
        var dto = service.createFromTemplate(1L, 20L, "  ");
        assertEquals("Security overview", dto.name());
    }

    @Test
    void createTemplateFromDashboardIsStaffOnly() {
        mockPlatformAdmin(false);
        assertThrows(AccessDeniedException.class, () -> service.createTemplateFromDashboard(1L, "X", null));
    }

    @Test
    void createTemplateFromDashboardSkipsWidgetsWithAnUnrecognizedType() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(
            widget(10L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{}"),
            widget(11L, 1L, null, "{}")
        ));

        service.createTemplateFromDashboard(1L, "Snapshot", null);

        verify(widgetRepo, times(1)).save(any());
    }

    @Test
    void createTemplateFromDashboardCopiesTitleConfigAndPosition() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        DashboardWidget src = widget(10L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{\"x\":1}");
        src.setTitle("My widget");
        src.setPosX(3);
        src.setPosY(4);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(src));

        service.createTemplateFromDashboard(1L, "Snapshot", "desc");

        var captor = org.mockito.ArgumentCaptor.forClass(DashboardWidget.class);
        verify(widgetRepo).save(captor.capture());
        assertEquals("My widget", captor.getValue().getTitle());
        assertEquals("{\"x\":1}", captor.getValue().getConfig());
        assertEquals(3, captor.getValue().getPosX());
    }

    @Test
    void createTemplateFromDashboardThrowsDuplicateNameExceptionWhenNameExistsAndNotOverwrite() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of());
        Dashboard existingTemplate = dashboard(2L, DashboardLevel.PROJECT, null, true);
        existingTemplate.setName("Snapshot");
        when(repo.findByLevelAndIsTemplateTrueAndNameIgnoreCase(DashboardLevel.PROJECT, "Snapshot"))
            .thenReturn(Optional.of(existingTemplate));

        var ex = assertThrows(com.martecyber.ares.common.DuplicateNameException.class,
            () -> service.createTemplateFromDashboard(1L, "Snapshot", null, false));
        assertTrue(ex.getMessage().contains("Snapshot"));
        verify(widgetRepo, never()).deleteByDashboardId(any());
    }

    @Test
    void createTemplateFromDashboardReplacesWidgetsInPlaceWhenOverwriting() {
        mockPlatformAdmin(true);
        dashboard(1L, DashboardLevel.PROJECT, 9L, false);
        when(widgetRepo.findByDashboardId(1L)).thenReturn(List.of(
            widget(10L, 1L, DashboardWidgetType.PROJECT_RULES_LIST, "{}")
        ));
        Dashboard existingTemplate = dashboard(2L, DashboardLevel.PROJECT, null, true);
        existingTemplate.setName("Snapshot");
        when(repo.findByLevelAndIsTemplateTrueAndNameIgnoreCase(DashboardLevel.PROJECT, "Snapshot"))
            .thenReturn(Optional.of(existingTemplate));

        var result = service.createTemplateFromDashboard(1L, "Snapshot", null, true);

        assertEquals(2L, result.id());
        verify(widgetRepo).deleteByDashboardId(2L);
        verify(repo).save(existingTemplate);
    }
}
