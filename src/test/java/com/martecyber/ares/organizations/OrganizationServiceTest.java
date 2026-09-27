package com.martecyber.ares.organizations;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingStatus;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.organizations.dto.CreateOrganizationRequest;
import com.martecyber.ares.organizations.dto.OrgDashboardDto;
import com.martecyber.ares.organizations.dto.OrganizationDto;
import com.martecyber.ares.organizations.dto.UpdateOrganizationRequest;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link OrganizationService} — slug-uniqueness on create,
 *  {@code updateProfile}'s restricted field set, logo validation, SLA parse/merge, and the
 *  dashboard's deadline-priority and stat-aggregation logic. No Spring context. */
class OrganizationServiceTest {

    private OrganizationRepository repo;
    private ProjectRepository projectRepo;
    private FindingRepository findingRepo;
    private FindingStatusRepository findingStatusRepo;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private OrganizationService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrganizationRepository.class);
        projectRepo = mock(ProjectRepository.class);
        findingRepo = mock(FindingRepository.class);
        findingStatusRepo = mock(FindingStatusRepository.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        service = new OrganizationService(repo, projectRepo, findingRepo, findingStatusRepo, workflowEventDispatcher);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Organization org(Long id, String settingsJson) {
        Organization o = new Organization();
        ReflectionTestUtils.setField(o, "id", id);
        o.setName("Acme");
        o.setSlug("acme");
        o.setStatus("active");
        o.setSettings(settingsJson);
        return o;
    }

    // ── create ───────────────────────────────────────────────────────

    @Test
    void createRejectsADuplicateSlugCaseInsensitively() {
        when(repo.existsBySlugIgnoreCase("Acme")).thenReturn(true);
        assertThrows(ConflictException.class,
            () -> service.create(new CreateOrganizationRequest("Acme Corp", "Acme", null)));
        verify(repo, never()).save(any());
    }

    @Test
    void createTrimsNameLowercasesSlugAndDefaultsStatusToActive() {
        when(repo.existsBySlugIgnoreCase("acme-corp")).thenReturn(false);
        OrganizationDto dto = service.create(new CreateOrganizationRequest("  Acme Corp  ", "ACME-CORP", null));

        assertEquals("Acme Corp", dto.name());
        assertEquals("acme-corp", dto.slug());
        assertEquals("active", dto.status());
        verify(workflowEventDispatcher).onOrganizationCreated(any());
    }

    // ── update / updateProfile ──────────────────────────────────────

    @Test
    void updateAppliesOnlyTheSuppliedFields() {
        Organization existing = org(1L, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        OrganizationDto dto = service.update(1L, new UpdateOrganizationRequest(null, "archived", null, null));

        assertEquals("Acme", dto.name()); // unchanged — name was null in the request
        assertEquals("archived", dto.status());
        verify(workflowEventDispatcher).onOrganizationUpdated(existing);
    }

    @Test
    void updateThrowsNotFoundForAnUnknownOrganization() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class,
            () -> service.update(99L, new UpdateOrganizationRequest("X", null, null, null)));
    }

    @Test
    void updateProfileIgnoresStatusAndRawSettingsEvenWhenSupplied() {
        Organization existing = org(1L, null);
        existing.setStatus("active");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        OrganizationDto dto = service.updateProfile(1L,
            new UpdateOrganizationRequest("New Name", "archived", "{\"malicious\":true}", null));

        assertEquals("New Name", dto.name());
        // A CLIENT_ADMIN can never archive their own org or inject raw settings via this path.
        assertEquals("active", dto.status());
        assertNull(dto.settings());
    }

    @Test
    void updateProfileStillAppliesSlaChanges() {
        Organization existing = org(1L, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        OrganizationDto dto = service.updateProfile(1L, new UpdateOrganizationRequest(
            null, null, null, new UpdateOrganizationRequest.SlaSettings(1, 2, 3, 4, 5)));

        assertEquals(1, dto.sla().critical());
        assertEquals(5, dto.sla().info());
    }

    // ── SLA merge (partial updates preserve previously-set values) ──

    @Test
    void slaMergePreservesFieldsNotIncludedInThePartialUpdate() {
        Organization existing = org(1L, "{\"sla\":{\"critical\":3,\"high\":10,\"medium\":20,\"low\":40,\"info\":0}}");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        // Only override "high" — everything else must survive from the existing settings.
        OrganizationDto dto = service.update(1L, new UpdateOrganizationRequest(
            null, null, null, new UpdateOrganizationRequest.SlaSettings(null, 15, null, null, null)));

        assertEquals(3, dto.sla().critical());
        assertEquals(15, dto.sla().high());
        assertEquals(20, dto.sla().medium());
        assertEquals(40, dto.sla().low());
    }

    @Test
    void parseSlaReturnsDefaultsForNullBlankOrMalformedSettings() {
        assertEquals(OrganizationDto.SlaSettings.defaults(), OrganizationService.parseSla(null));
        assertEquals(OrganizationDto.SlaSettings.defaults(), OrganizationService.parseSla(""));
        assertEquals(OrganizationDto.SlaSettings.defaults(), OrganizationService.parseSla("not json"));
        assertEquals(OrganizationDto.SlaSettings.defaults(), OrganizationService.parseSla("{\"other\":1}"));
    }

    @Test
    void parseSlaReadsExplicitValues() {
        var sla = OrganizationService.parseSla("{\"sla\":{\"critical\":1,\"high\":2,\"medium\":3,\"low\":4,\"info\":5}}");
        assertEquals(1, sla.critical());
        assertEquals(5, sla.info());
    }

    // ── Logo ─────────────────────────────────────────────────────────

    @Test
    void uploadLogoRejectsAnEmptyFile() {
        Organization existing = org(1L, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        MultipartFile empty = new MockMultipartFile("logo", "logo.png", "image/png", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.uploadLogo(1L, empty));
    }

    @Test
    void uploadLogoRejectsFilesOverTwoMegabytes() {
        MultipartFile tooBig = new MockMultipartFile("logo", "logo.png", "image/png", new byte[2 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> service.uploadLogo(1L, tooBig));
    }

    @Test
    void uploadLogoRejectsNonImageMimeTypes() {
        MultipartFile notAnImage = new MockMultipartFile("logo", "evil.pdf", "application/pdf", new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> service.uploadLogo(1L, notAnImage));
    }

    @Test
    void uploadLogoStoresBytesAndMimeOnSuccess() throws Exception {
        Organization existing = org(1L, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        byte[] bytes = {1, 2, 3, 4};
        service.uploadLogo(1L, new MockMultipartFile("logo", "logo.png", "image/png", bytes));

        assertArrayEquals(bytes, existing.getLogoData());
        assertEquals("image/png", existing.getLogoMime());
    }

    @Test
    void deleteLogoClearsDataAndMime() {
        Organization existing = org(1L, null);
        existing.setLogoData(new byte[]{1});
        existing.setLogoMime("image/png");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        service.deleteLogo(1L);

        assertNull(existing.getLogoData());
        assertNull(existing.getLogoMime());
    }

    @Test
    void getLogoThrowsNotFoundWhenNoLogoIsStored() {
        when(repo.findById(1L)).thenReturn(Optional.of(org(1L, null)));
        assertThrows(NotFoundException.class, () -> service.getLogo(1L));
    }

    @Test
    void getLogoDefaultsMimeToPngWhenMissing() {
        Organization existing = org(1L, null);
        existing.setLogoData(new byte[]{9});
        existing.setLogoMime(null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        var logo = service.getLogo(1L);
        assertEquals("image/png", logo.mime());
    }

    // ── archive ──────────────────────────────────────────────────────

    @Test
    void archiveSetsStatusToArchived() {
        Organization existing = org(1L, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        service.archive(1L);
        assertEquals("archived", existing.getStatus());
    }

    // ── list ─────────────────────────────────────────────────────────

    @Test
    void listWithNoStatusFilterUsesTheUnfilteredOrderedQuery() {
        when(repo.findAllByOrderByCreatedAtDesc(any())).thenReturn(new PageImpl<>(List.of(org(1L, null))));
        Page<OrganizationDto> page = service.list(null, 0, 20, null);
        assertEquals(1, page.getTotalElements());
        verify(repo, never()).findByStatusOrderByCreatedAtDesc(anyString(), any());
    }

    @Test
    void listWithAStatusFilterDelegatesToTheFilteredQuery() {
        when(repo.findByStatusOrderByCreatedAtDesc(eq("archived"), any())).thenReturn(new PageImpl<>(List.of()));
        service.list("archived", 0, 20, null);
        verify(repo).findByStatusOrderByCreatedAtDesc(eq("archived"), any());
    }

    @Test
    void listForAnOperatorUserDelegatesToTheOperatorScopedQuery() {
        when(repo.findByOperatorUser(eq(42L), any(), any())).thenReturn(new PageImpl<>(List.of()));
        service.list(null, 0, 20, 42L);
        verify(repo).findByOperatorUser(eq(42L), isNull(), any());
        verify(repo, never()).findAllByOrderByCreatedAtDesc(any());
    }

    // ── Dashboard ────────────────────────────────────────────────────

    private Finding finding(Long id, String severity, LocalDate dueDate, OffsetDateTime reportedAt, Long statusId) {
        Finding f = new Finding();
        ReflectionTestUtils.setField(f, "id", id);
        f.setProjectId(10L);
        f.setSeverity(severity);
        f.setTitle("F" + id);
        f.setCode("F-" + id);
        f.setDueDate(dueDate);
        f.setReportedAt(reportedAt);
        f.setStatusId(statusId);
        return f;
    }

    private FindingStatus status(Long id, String name) {
        FindingStatus s = new FindingStatus();
        ReflectionTestUtils.setField(s, "id", id);
        s.setName(name);
        return s;
    }

    @Test
    void dashboardCountsFindingsBySeverity() {
        Organization o = org(5L, null);
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of());
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(List.of(
            finding(1L, "critical", null, null, 1L),
            finding(2L, "critical", null, null, 1L),
            finding(3L, "high", null, null, 1L),
            finding(4L, "low", null, null, 1L)
        ));
        when(findingStatusRepo.findAll()).thenReturn(List.of());

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(2, dashboard.stats().critical());
        assertEquals(1, dashboard.stats().high());
        assertEquals(0, dashboard.stats().medium());
        assertEquals(1, dashboard.stats().low());
    }

    @Test
    void dashboardDeadlinePrefersTheExplicitDueDateOverTheSlaComputedOne() {
        Organization o = org(5L, "{\"sla\":{\"critical\":7,\"high\":30,\"medium\":90,\"low\":180,\"info\":0}}");
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of());
        LocalDate explicitDue = LocalDate.now().plusDays(1);
        // Reported long ago (SLA would already say "overdue" for a critical), but an explicit
        // due date 1 day out must win and keep it out of slaPassed.
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(List.of(
            finding(1L, "critical", explicitDue, OffsetDateTime.now().minusDays(30), 1L)
        ));
        when(findingStatusRepo.findAll()).thenReturn(List.of(status(1L, "Open")));

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(0, dashboard.stats().slaPassed());
        assertEquals(1, dashboard.stats().dueIn7Days());
        assertEquals(explicitDue, dashboard.urgentFindings().get(0).slaDeadline());
    }

    @Test
    void dashboardFallsBackToSlaComputedDeadlineWhenNoExplicitDueDate() {
        Organization o = org(5L, "{\"sla\":{\"critical\":7,\"high\":30,\"medium\":90,\"low\":180,\"info\":0}}");
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of());
        // Reported 10 days ago, critical SLA is 7 days -> already 3 days past the computed deadline.
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(List.of(
            finding(1L, "critical", null, OffsetDateTime.now().minusDays(10), 1L)
        ));
        when(findingStatusRepo.findAll()).thenReturn(List.of(status(1L, "Open")));

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(1, dashboard.stats().slaPassed());
        assertEquals(0, dashboard.stats().dueIn7Days());
    }

    @Test
    void dashboardFindingsWithNoResolvableDeadlineAreExcludedFromUrgentListAndCounts() {
        Organization o = org(5L, null);
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of());
        // No dueDate and no reportedAt -> deadline() returns null for both branches.
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(List.of(
            finding(1L, "critical", null, null, 1L)
        ));
        when(findingStatusRepo.findAll()).thenReturn(List.of());

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(0, dashboard.stats().dueIn7Days());
        assertEquals(0, dashboard.stats().slaPassed());
        assertTrue(dashboard.urgentFindings().isEmpty());
    }

    @Test
    void dashboardUrgentListIsSortedBySoonestDeadlineAndLimitedToTen() {
        Organization o = org(5L, "{\"sla\":{\"critical\":7,\"high\":30,\"medium\":90,\"low\":180,\"info\":0}}");
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of());
        List<Finding> findings = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // Later findings (higher i) have an earlier due date -> should sort to the front.
            findings.add(finding((long) i, "low", LocalDate.now().plusDays(20 - i), null, 1L));
        }
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(findings);
        when(findingStatusRepo.findAll()).thenReturn(List.of(status(1L, "Open")));

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(10, dashboard.urgentFindings().size());
        assertEquals(11L, dashboard.urgentFindings().get(0).id()); // i=11 has the soonest due date
        assertEquals("Open", dashboard.urgentFindings().get(0).statusName());
    }

    @Test
    void dashboardMapsActiveProjectsWithComputedStatus() {
        Organization o = org(5L, null);
        when(repo.findById(5L)).thenReturn(Optional.of(o));
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", 7L);
        p.setName("Pentest Q1");
        p.setCode("ACME-PT-01");
        p.setStartDate(LocalDate.now().minusDays(1));
        when(projectRepo.findActiveByOrg(5L)).thenReturn(List.of(p));
        when(findingRepo.findOpenPublishedByOrgId(5L)).thenReturn(List.of());
        when(findingStatusRepo.findAll()).thenReturn(List.of());

        OrgDashboardDto dashboard = service.getDashboard(5L);
        assertEquals(1, dashboard.activeProjects().size());
        assertEquals("Pentest Q1", dashboard.activeProjects().get(0).name());
        assertEquals("active", dashboard.activeProjects().get(0).status());
    }

    @Test
    void getDashboardThrowsNotFoundForAnUnknownOrganization() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.getDashboard(99L));
    }
}
