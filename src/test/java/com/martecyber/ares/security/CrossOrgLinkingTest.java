package com.martecyber.ares.security;

import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.integrations.IntegrationController;
import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.integrations.dto.IntegrationDto;
import com.martecyber.ares.integrations.dto.IntegrationGrantDto;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectRetestService;
import com.martecyber.ares.projects.ProjectType;
import com.martecyber.ares.projects.ProjectTypeRepository;
import com.martecyber.ares.reporting.ReportGenerationService;
import com.martecyber.ares.reporting.dto.GenerateReportRequest;
import com.martecyber.ares.users.OrgScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Data of one organization must never be attached to another organization's records — even by a
 * caller who legitimately has access to the target, since the attached data then becomes readable
 * by everyone who can see that target.
 */
class CrossOrgLinkingTest {

    /** Builds {@code type} through its (single) constructor, using {@code given} where provided and mocks elsewhere. */
    private static <T> T build(Class<T> type, Object... given) {
        Constructor<?> ctor = type.getConstructors()[0];
        Object[] deps = Arrays.stream(ctor.getParameterTypes())
            .map(t -> Arrays.stream(given).filter(t::isInstance).findFirst().orElseGet(() -> mock(t)))
            .toArray();
        try {
            return type.cast(ctor.newInstance(deps));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Project project(long id, long orgId, Long typeId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setOrganizationId(orgId);
        p.setTypeId(typeId);
        return p;
    }

    /** A report's organization decides who can read it, so it must be the project's own. */
    @Test
    void reportCannotBeFiledUnderAnotherOrganization() {
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(1L)).thenReturn(Optional.of(project(1, 10, null)));
        ReportGenerationService svc = build(ReportGenerationService.class, projects);

        GenerateReportRequest req = mock(GenerateReportRequest.class);
        when(req.projectId()).thenReturn(1L);
        when(req.organizationId()).thenReturn(20L);

        assertThrows(IllegalArgumentException.class, () -> svc.create(req));
    }

    /** A retest re-verifies the client's own findings; a foreign one would show up in its reports. */
    @Test
    void retestCannotLinkAnotherOrganizationsFinding() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectTypeRepository types = mock(ProjectTypeRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        var links = mock(com.martecyber.ares.projects.ProjectRetestFindingRepository.class);

        ProjectType retest = new ProjectType();
        retest.setCode("RETEST");
        when(types.findById(5L)).thenReturn(Optional.of(retest));
        when(projects.findById(1L)).thenReturn(Optional.of(project(1, 10, 5L)));
        Finding foreign = new Finding();
        foreign.setProjectId(2L);
        when(findings.findById(77L)).thenReturn(Optional.of(foreign));
        when(projects.findOrganizationIdById(2L)).thenReturn(Optional.of(20L));

        ProjectRetestService svc = build(ProjectRetestService.class, projects, types, findings, links,
            mock(OrgScopeService.class));

        assertThrows(IllegalArgumentException.class, () -> svc.link(1L, List.of(77L)));
        verify(links, never()).save(any());
    }

    /** A platform integration is granted to many orgs; each caller only sees their own grants. */
    @Test
    void integrationGrantsAreFilteredToTheCallersOrganizations() {
        IntegrationService svc = mock(IntegrationService.class);
        when(svc.get(1L)).thenReturn(new IntegrationDto(1L, null, "x", "x", "PLATFORM", null,
            null, null, null, null, null, null, null));
        when(svc.listGrants(1L)).thenReturn(List.of(grant(10L), grant(20L)));

        OrgScopeService orgScope = mock(OrgScopeService.class);
        when(orgScope.currentAccessibleOrgIds()).thenReturn(Set.of(10L));
        when(orgScope.canSeeOrgOwned(any(), any())).thenCallRealMethod();

        IntegrationController controller = build(IntegrationController.class, svc, orgScope);

        assertEquals(List.of(10L), controller.listGrants(1L).stream().map(IntegrationGrantDto::organizationId).toList());
    }

    private static IntegrationGrantDto grant(Long orgId) {
        return new IntegrationGrantDto(orgId, 1L, orgId, null, List.of(), null, null, null, null, true, null, null);
    }

    @Test
    void orgOwnedVisibilityRules() {
        OrgScopeService o = new OrgScopeService(null, null, null, null);
        assertTrue(o.canSeeOrgOwned(Set.of(), null), "platform-wide records are visible to all staff");
        assertTrue(o.canSeeOrgOwned(null, 20L), "null accessible set = platform admin");
        assertTrue(o.canSeeOrgOwned(Set.of(20L), 20L));
        assertFalse(o.canSeeOrgOwned(Set.of(10L), 20L));
    }
}
