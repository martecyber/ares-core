package com.martecyber.ares.projects;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.dto.*;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test for {@link ProjectService} — list/get scope-access branching, project
 *  code generation (Bug Hunting / MONITOR / generic dated formats), the retest-requires-dates
 *  rule on create/update, iteration-cadence clear-vs-overwrite semantics, scope-entry
 *  normalization/scheduling, member management (lead-uniqueness, duplicate-role rejection), and
 *  the {@code computeStatus}/type-hierarchy helpers. No Spring context. */
class ProjectServiceTest {

    private ProjectRepository repo;
    private ProjectScopeEntryRepository scopeRepo;
    private ProjectMemberRepository memberRepo;
    private ProjectTypeRepository typeRepo;
    private OrganizationRepository orgRepo;
    private UserRepository userRepo;
    private ScopeEntryAssetDeriver deriver;
    private AssetScopeClassifier classifier;
    private ScopeClassifyScheduler scheduler;
    private OrgScopeService orgScope;
    private WorkflowEventDispatcher workflowEventDispatcher;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectRepository.class);
        scopeRepo = mock(ProjectScopeEntryRepository.class);
        memberRepo = mock(ProjectMemberRepository.class);
        typeRepo = mock(ProjectTypeRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        userRepo = mock(UserRepository.class);
        deriver = mock(ScopeEntryAssetDeriver.class);
        classifier = mock(AssetScopeClassifier.class);
        scheduler = mock(ScopeClassifyScheduler.class);
        orgScope = mock(OrgScopeService.class);
        workflowEventDispatcher = mock(WorkflowEventDispatcher.class);
        service = new ProjectService(repo, scopeRepo, memberRepo, typeRepo, orgRepo, userRepo,
            deriver, classifier, scheduler, orgScope, workflowEventDispatcher);

        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scopeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(typeRepo.findAll()).thenReturn(List.of());
        setAuth("7");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication setAuth(String userId, String... authorities) {
        List<GrantedAuthority> auths = List.of(authorities).stream().map(SimpleGrantedAuthority::new).map(a -> (GrantedAuthority) a).toList();
        var auth = new UsernamePasswordAuthenticationToken(userId, null, auths);
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private Project project(Long id, Long orgId, Long typeId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setOrganizationId(orgId);
        p.setTypeId(typeId);
        p.setName("Project " + id);
        p.setCode("CODE-" + id);
        when(repo.findById(id)).thenReturn(Optional.of(p));
        when(repo.existsById(id)).thenReturn(true);
        return p;
    }

    private ProjectType type(Long id, String code, Long supertypeId) {
        ProjectType t = new ProjectType();
        ReflectionTestUtils.setField(t, "id", id);
        t.setCode(code);
        t.setSupertypeId(supertypeId);
        when(typeRepo.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private Organization org(Long id, String slug) {
        Organization o = new Organization();
        ReflectionTestUtils.setField(o, "id", id);
        o.setName("Org " + id);
        o.setSlug(slug);
        when(orgRepo.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    // ── list() ───────────────────────────────────────────────────────

    @Test
    void listWithAnOrganizationIdAssertsOrgAccessAndFiltersByThatOrg() {
        var auth = setAuth("7");
        when(repo.filter(eq(5L), isNull(), any())).thenReturn(Page.empty());
        service.list(5L, "  ", 0, 20, auth);
        verify(orgScope).assertOrgAccess(auth, 5L);
        verify(repo).filter(eq(5L), isNull(), any());
    }

    @Test
    void listWithoutAnOrganizationIdAsAPlatformAdminSeesEverything() {
        var auth = setAuth("7");
        when(orgScope.isPlatformAdmin(auth)).thenReturn(true);
        when(repo.filter(isNull(), any(), any())).thenReturn(Page.empty());
        service.list(null, null, 0, 20, auth);
        verify(repo).filter(isNull(), any(), any());
        verify(repo, never()).filterByOrgIds(any(), any(), any());
    }

    @Test
    void listWithoutAnOrganizationIdAsARegularUserIsScopedToAccessibleOrgs() {
        var auth = setAuth("7");
        when(orgScope.isPlatformAdmin(auth)).thenReturn(false);
        when(orgScope.accessibleOrgIds(auth)).thenReturn(Set.of(5L, 6L));
        when(repo.filterByOrgIds(any(), any(), any())).thenReturn(Page.empty());
        service.list(null, null, 0, 20, auth);
        verify(repo).filterByOrgIds(argThat(ids -> ids.containsAll(List.of(5L, 6L))), any(), any());
    }

    @Test
    void listWithoutAnOrganizationIdAndNoAccessibleOrgsReturnsAnEmptyPageWithoutQuerying() {
        var auth = setAuth("7");
        when(orgScope.isPlatformAdmin(auth)).thenReturn(false);
        when(orgScope.accessibleOrgIds(auth)).thenReturn(Set.of());
        var page = service.list(null, null, 0, 20, auth);
        assertTrue(page.isEmpty());
        verify(repo, never()).filterByOrgIds(any(), any(), any());
    }

    // ── get() ────────────────────────────────────────────────────────

    @Test
    void getThrowsNotFoundForAnUnknownProject() {
        var auth = setAuth("7");
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.get(1L, auth));
    }

    @Test
    void getAssertsProjectAccessAndResolvesOrgNameScopeAndMembers() {
        var auth = setAuth("7");
        project(1L, 5L, null);
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());

        var dto = service.get(1L, auth);

        verify(orgScope).assertProjectAccess(auth, 1L);
        assertEquals("Org 5", dto.organizationName());
    }

    // ── create() ─────────────────────────────────────────────────────

    @Test
    void createThrowsNotFoundForAnUnknownOrganization() {
        when(orgRepo.findById(5L)).thenReturn(Optional.empty());
        var req = new CreateProjectRequest(5L, "X", null, null, null, null, null, null, false);
        assertThrows(NotFoundException.class, () -> service.create(req));
    }

    @Test
    void createThrowsNotFoundForAnUnknownProjectType() {
        org(5L, "acme");
        when(typeRepo.findById(9L)).thenReturn(Optional.empty());
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertThrows(NotFoundException.class, () -> service.create(req));
    }

    @Test
    void createRejectsARetestProjectWithoutBothDates() {
        org(5L, "acme");
        type(9L, "RETEST", null);
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertThrows(ResponseStatusException.class, () -> service.create(req));
    }

    @Test
    void createAllowsARetestProjectWithBothDates() {
        org(5L, "acme");
        type(9L, "RETEST", null);
        var req = new CreateProjectRequest(5L, "X", 9L, null, LocalDate.now(), LocalDate.now().plusDays(5), null, null, false);
        assertDoesNotThrow(() -> service.create(req));
    }

    @Test
    void createUsesTheProvidedCodeUppercasedAndTrimmed() {
        org(5L, "acme");
        var req = new CreateProjectRequest(5L, "X", null, "  my-code  ", null, null, null, null, false);
        var dto = service.create(req);
        assertEquals("MY-CODE", dto.code());
    }

    @Test
    void createDispatchesOnProjectCreated() {
        org(5L, "acme");
        var req = new CreateProjectRequest(5L, "X", null, "C1", null, null, null, null, false);
        service.create(req);
        verify(workflowEventDispatcher).onProjectCreated(any());
    }

    @Test
    void createTrimsTheProjectName() {
        org(5L, "acme");
        var req = new CreateProjectRequest(5L, "  Spaced Name  ", null, "C1", null, null, null, null, false);
        var dto = service.create(req);
        assertEquals("Spaced Name", dto.name());
    }

    // ── generateProjectCode (via create/previewCode) ─────────────────

    @Test
    void generatesABugHuntingCodeWithNoYearComponent() {
        org(5L, "acme");
        type(9L, "BH", null).setContinuousNumbering(true);
        when(repo.countByOrgAndTypeCode(5L, "BH")).thenReturn(2L);
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertEquals("ACME-BH-03", service.create(req).code());
    }

    @Test
    void generatesABugHuntingCodeForASubtypeOfTheBhMaster() {
        org(5L, "acme");
        type(20L, "BH", null).setContinuousNumbering(true);       // master
        type(9L, "BH-WEB", 20L);     // subtype
        when(repo.countByOrgAndTypeCode(5L, "BH-WEB")).thenReturn(0L);
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertEquals("ACME-BH-WEB-01", service.create(req).code());
    }

    @Test
    void generatesTheRootMonitorCodeSharedAcrossTheWholeMonitorFamily() {
        org(5L, "acme");
        type(9L, "MONITOR", null);
        when(repo.countByOrgMonitorType(5L)).thenReturn(4L);
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertEquals("ACME-MONITOR-05", service.create(req).code());
    }

    @Test
    void generatesAUserDefinedMonitorSubtypeCodeWithItsOwnSequence() {
        org(5L, "acme");
        type(20L, "MONITOR", null);
        type(9L, "EASM", 20L);
        when(repo.countByOrgAndTypeCode(5L, "EASM")).thenReturn(1L);
        var req = new CreateProjectRequest(5L, "X", 9L, null, null, null, null, null, false);
        assertEquals("ACME-EASM-02", service.create(req).code());
    }

    @Test
    void generatesAGenericDatedCodeWhenNoTypeIsGiven() {
        org(5L, "acme");
        int year = LocalDate.now().getYear();
        when(repo.countByOrgYear(eq(5L), eq(year))).thenReturn(0L);
        var req = new CreateProjectRequest(5L, "X", null, null, null, null, null, null, false);
        String code = service.create(req).code();
        assertTrue(code.startsWith("ACME-GEN-"), code);
        assertTrue(code.endsWith("-01"), code);
    }

    @Test
    void previewCodeToleratesAnUnknownOrganizationAndType() {
        when(orgRepo.findById(99L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.previewCode(99L, null));
    }

    // ── update() ─────────────────────────────────────────────────────

    @Test
    void updateOnlyAppliesSuppliedFields() {
        Project p = project(1L, 5L, null);
        p.setName("Original");
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());

        service.update(1L, new UpdateProjectRequest(null, null, null, null, null, null, null, null));

        assertEquals("Original", p.getName());
    }

    @Test
    void updateOwnerUserIdZeroClearsTheOwner() {
        Project p = project(1L, 5L, null);
        p.setOwnerUserId(42L);
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());

        service.update(1L, new UpdateProjectRequest(null, null, null, null, 0L, null, null, null));

        assertNull(p.getOwnerUserId());
    }

    @Test
    void updateExplicitNullIterationCadenceClearsAnExistingOne() {
        Project p = project(1L, 5L, null);
        p.setIterationCadence("weekly");
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());

        // req.iterationCadence() is null but the project already has one -> gets cleared per the
        // "explicit null clears it" contract (distinct from "field omitted, leave unchanged").
        service.update(1L, new UpdateProjectRequest(null, null, null, null, null, null, null, null));

        assertNull(p.getIterationCadence());
    }

    @Test
    void updateRejectsSwitchingToARetestTypeWithoutBothDatesAlreadySet() {
        Project p = project(1L, 5L, null);
        type(9L, "RETEST", null);
        assertThrows(ResponseStatusException.class,
            () -> service.update(1L, new UpdateProjectRequest(null, 9L, null, null, null, null, null, null)));
    }

    @Test
    void updateAllowsSwitchingToARetestTypeWhenBothDatesAreAlreadyPresent() {
        Project p = project(1L, 5L, null);
        p.setStartDate(LocalDate.now());
        p.setEndDate(LocalDate.now().plusDays(1));
        type(9L, "RETEST", null);
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        assertDoesNotThrow(() -> service.update(1L, new UpdateProjectRequest(null, 9L, null, null, null, null, null, null)));
    }

    @Test
    void updateDispatchesOnProjectUpdated() {
        project(1L, 5L, null);
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        service.update(1L, new UpdateProjectRequest("New name", null, null, null, null, null, null, null));
        verify(workflowEventDispatcher).onProjectUpdated(any());
    }

    // ── complete() / reopen() ────────────────────────────────────────

    @Test
    void completeThrowsConflictWhenAlreadyCompleted() {
        Project p = project(1L, 5L, null);
        p.setCompletedAt(OffsetDateTime.now());
        assertThrows(ConflictException.class, () -> service.complete(1L));
    }

    @Test
    void completeSetsCompletedAt() {
        Project p = project(1L, 5L, null);
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        service.complete(1L);
        assertNotNull(p.getCompletedAt());
    }

    @Test
    void reopenThrowsConflictWhenNotCompleted() {
        project(1L, 5L, null);
        assertThrows(ConflictException.class, () -> service.reopen(1L));
    }

    @Test
    void reopenClearsCompletedAt() {
        Project p = project(1L, 5L, null);
        p.setCompletedAt(OffsetDateTime.now());
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        service.reopen(1L);
        assertNull(p.getCompletedAt());
    }

    // ── approveIteration() ───────────────────────────────────────────

    @Test
    void approveIterationRejectsAProjectWithNoCadenceConfigured() {
        project(1L, 5L, null);
        assertThrows(IllegalArgumentException.class, () -> service.approveIteration(1L));
    }

    @Test
    void approveIterationComputesAndSetsTheActiveLabel() {
        Project p = project(1L, 5L, null);
        p.setIterationCadence("monthly");
        org(5L, "acme");
        when(scopeRepo.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        service.approveIteration(1L);
        assertNotNull(p.getActiveIterationLabel());
    }

    // ── delete() ─────────────────────────────────────────────────────

    @Test
    void deleteThrowsNotFoundForAnUnknownProject() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.delete(1L));
    }

    @Test
    void deleteDispatchesOnProjectDeletedWithTheOrganizationId() {
        project(1L, 5L, null);
        service.delete(1L);
        verify(workflowEventDispatcher).onProjectDeleted(1L, 5L);
        verify(repo).deleteById(1L);
    }

    // ── addScopeEntries() / removeScopeEntry() ───────────────────────

    @Test
    void addScopeEntriesReturnsEmptyForABlankValueWithoutTouchingTheRepo() {
        project(1L, 5L, null);
        var req = new AddScopeEntryRequest("domain", "   ", null, null, null);
        assertTrue(service.addScopeEntries(1L, req).isEmpty());
        verify(scopeRepo, never()).save(any());
    }

    @Test
    void addScopeEntriesSavesDerivesAndSchedulesReclassification() {
        Project p = project(1L, 5L, null);
        var req = new AddScopeEntryRequest("domain", "example.com", null, null, null);

        var result = service.addScopeEntries(1L, req);

        assertEquals(1, result.size());
        assertTrue(result.get(0).inScope()); // defaults to true when null
        verify(deriver).derive(any(), eq(5L));
        verify(scheduler).schedule(1L);
    }

    @Test
    void addScopeEntriesHonorsAnExplicitInScopeFalse() {
        project(1L, 5L, null);
        var req = new AddScopeEntryRequest("domain", "example.com", null, null, false);
        var result = service.addScopeEntries(1L, req);
        assertFalse(result.get(0).inScope());
    }

    @Test
    void removeScopeEntryThrowsNotFoundWhenTheProjectDoesNotExist() {
        when(repo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.removeScopeEntry(1L, 10L));
    }

    @Test
    void removeScopeEntryDeletesAndSchedulesReclassification() {
        project(1L, 5L, null);
        service.removeScopeEntry(1L, 10L);
        verify(scopeRepo).deleteByProjectIdAndId(1L, 10L);
        verify(scheduler).schedule(1L);
    }

    // ── addMember() / removeMember() ─────────────────────────────────

    private User user(Long id, String email, String name) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail(email);
        u.setDisplayName(name);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void addMemberThrowsNotFoundWhenTheProjectDoesNotExist() {
        when(repo.existsById(1L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.addMember(1L, new AddMemberRequest(9L, "operator")));
    }

    @Test
    void addMemberDefaultsABlankRoleToOperator() {
        project(1L, 5L, null);
        user(9L, "a@x.com", "Alice");
        var dto = service.addMember(1L, new AddMemberRequest(9L, "  "));
        assertEquals("operator", dto.role());
    }

    @Test
    void addMemberRejectsADuplicateProjectUserRoleTriple() {
        project(1L, 5L, null);
        when(memberRepo.existsById(new ProjectMemberId(1L, 9L, "operator"))).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.addMember(1L, new AddMemberRequest(9L, "operator")));
    }

    @Test
    void addMemberThrowsNotFoundForAnUnknownUser() {
        project(1L, 5L, null);
        when(userRepo.findById(9L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.addMember(1L, new AddMemberRequest(9L, "operator")));
    }

    @Test
    void addMemberRejectsASecondLead() {
        project(1L, 5L, null);
        user(9L, "a@x.com", "Alice");
        ProjectMember existingLead = new ProjectMember(1L, 1L, "lead");
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of(existingLead));
        assertThrows(ConflictException.class, () -> service.addMember(1L, new AddMemberRequest(9L, "lead")));
    }

    @Test
    void addMemberSucceedsAndReturnsTheMemberDto() {
        project(1L, 5L, null);
        user(9L, "a@x.com", "Alice");
        var dto = service.addMember(1L, new AddMemberRequest(9L, "operator"));
        assertEquals("Alice", dto.userDisplayName());
        assertEquals("a@x.com", dto.userEmail());
    }

    @Test
    void removeMemberThrowsNotFoundWhenTheMembershipDoesNotExist() {
        when(memberRepo.existsById(new ProjectMemberId(1L, 9L, "operator"))).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.removeMember(1L, 9L, "operator"));
    }

    @Test
    void removeMemberDeletesAnExistingMembership() {
        when(memberRepo.existsById(new ProjectMemberId(1L, 9L, "operator"))).thenReturn(true);
        service.removeMember(1L, 9L, "operator");
        verify(memberRepo).deleteById(new ProjectMemberId(1L, 9L, "operator"));
    }

    // ── getMyProjectRole() ───────────────────────────────────────────

    @Test
    void getMyProjectRoleReturnsNullWhenNotAuthenticated() {
        SecurityContextHolder.clearContext();
        assertNull(service.getMyProjectRole(1L));
    }

    @Test
    void getMyProjectRoleReturnsAdminForMsspAdminRegardlessOfMembership() {
        setAuth("7", "ROLE_MSSP_ADMIN");
        assertEquals("admin", service.getMyProjectRole(1L));
        verifyNoInteractions(memberRepo);
    }

    @Test
    void getMyProjectRoleReturnsLeadWhenTheUserHasALeadRow() {
        setAuth("7");
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of(new ProjectMember(1L, 7L, "lead")));
        assertEquals("lead", service.getMyProjectRole(1L));
    }

    @Test
    void getMyProjectRoleReturnsOperatorWhenOnlyAnOperatorRow() {
        setAuth("7");
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of(new ProjectMember(1L, 7L, "operator")));
        assertEquals("operator", service.getMyProjectRole(1L));
    }

    @Test
    void getMyProjectRoleReturnsNullWithNoMembershipAtAll() {
        setAuth("7");
        when(memberRepo.findByIdProjectId(1L)).thenReturn(List.of());
        assertNull(service.getMyProjectRole(1L));
    }

    // ── computeStatus() ──────────────────────────────────────────────

    @Test
    void computeStatusIsCompletedWhenCompletedAtIsSetRegardlessOfDates() {
        Project p = new Project();
        p.setCompletedAt(OffsetDateTime.now());
        p.setEndDate(LocalDate.now().minusYears(1));
        assertEquals("completed", ProjectService.computeStatus(p));
    }

    @Test
    void computeStatusIsScheduledWithNoStartDate() {
        assertEquals("scheduled", ProjectService.computeStatus(new Project()));
    }

    @Test
    void computeStatusIsScheduledWhenStartIsInTheFuture() {
        Project p = new Project();
        p.setStartDate(LocalDate.now().plusDays(5));
        assertEquals("scheduled", ProjectService.computeStatus(p));
    }

    @Test
    void computeStatusIsPastDueWhenEndDateHasPassed() {
        Project p = new Project();
        p.setStartDate(LocalDate.now().minusDays(10));
        p.setEndDate(LocalDate.now().minusDays(1));
        assertEquals("past_due", ProjectService.computeStatus(p));
    }

    @Test
    void computeStatusIsActiveWithinTheDateRange() {
        Project p = new Project();
        p.setStartDate(LocalDate.now().minusDays(1));
        p.setEndDate(LocalDate.now().plusDays(1));
        assertEquals("active", ProjectService.computeStatus(p));
    }

    // ── Type-hierarchy helpers ───────────────────────────────────────

    @Test
    void isMonitoringTypeIsTrueForTheMonitorMasterItself() {
        assertTrue(service.isMonitoringType(type(1L, "MONITOR", null)));
    }

    @Test
    void isMonitoringTypeIsTrueForASubtypeOfMonitor() {
        type(1L, "MONITOR", null);
        assertTrue(service.isMonitoringType(type(2L, "EASM", 1L)));
    }

    @Test
    void isMonitoringTypeIsFalseForUnrelatedTypesAndNull() {
        assertFalse(service.isMonitoringType(type(1L, "ASSESS", null)));
        assertFalse(service.isMonitoringType(null));
    }

    @Test
    void isMonitorProjectIsFalseWhenTheProjectHasNoType() {
        project(1L, 5L, null);
        assertFalse(service.isMonitorProject(1L));
    }

    @Test
    void isMonitorProjectIsTrueWhenItsTypeIsAMonitorSubtype() {
        type(1L, "MONITOR", null);
        project(1L, 5L, 2L);
        type(2L, "EASM", 1L);
        assertTrue(service.isMonitorProject(1L));
    }

    @Test
    void isAssessmentTypeIsTrueForTheAssessMaster() {
        assertTrue(service.isAssessmentType(type(1L, "ASSESS", null)));
    }

    @Test
    void isRetestTypeIsTrueForARetestSubtype() {
        type(1L, "RETEST", null);
        assertTrue(service.isRetestType(type(2L, "RETEST-WEB", 1L)));
    }

    @Test
    void isRetestProjectIsFalseWhenTheProjectDoesNotExist() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertFalse(service.isRetestProject(99L));
    }
}
