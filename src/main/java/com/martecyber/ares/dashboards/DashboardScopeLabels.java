package com.martecyber.ares.dashboards;

import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Resolves a human-readable "where does this dashboard live" label ("Platform" / an org name /
 *  "&lt;org&gt; / &lt;project&gt;"), plus the dashboard's effective organization id (null for PLATFORM,
 *  {@code scopeId} itself for ORGANIZATION, the project's own organization id for PROJECT — the
 *  latter needed by the presentation player to embed a PROJECT-level dashboard's {@code
 *  DashboardHost} with a correct {@code orgId} for its widgets' click-through routes, which
 *  {@code Dashboard.scopeId} alone doesn't carry), for a batch of dashboards in a small fixed
 *  number of queries (not one per dashboard). Shared by the dashboard-browse picker ({@link
 *  DashboardService}) and presentation-item resolution ({@link DashboardPresentationService}),
 *  both of which show dashboards spanning every level in one readable list (a presentation freely
 *  mixes platform/organization/project dashboards). */
final class DashboardScopeLabels {

    private DashboardScopeLabels() {}

    record ScopeInfo(String label, Long organizationId) {}

    static Map<Long, ScopeInfo> resolve(List<Dashboard> dashboards, OrganizationRepository orgRepo, ProjectRepository projectRepo) {
        List<Long> orgIds = dashboards.stream()
            .filter(d -> d.getLevel() == DashboardLevel.ORGANIZATION)
            .map(Dashboard::getScopeId).distinct().toList();
        List<Long> projectIds = dashboards.stream()
            .filter(d -> d.getLevel() == DashboardLevel.PROJECT)
            .map(Dashboard::getScopeId).distinct().toList();

        Map<Long, String> orgNames = orgRepo.findAllById(orgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), o -> o.getName()));
        List<Project> projects = projectRepo.findAllById(projectIds);
        Map<Long, Project> projectsById = projects.stream().collect(Collectors.toMap(Project::getId, p -> p));
        // A project's own organization might have no dashboard of its own in this batch, so its
        // name has to be looked up separately rather than reused from orgNames.
        List<Long> projectOrgIds = projects.stream().map(Project::getOrganizationId).distinct().toList();
        Map<Long, String> projectOrgNames = orgRepo.findAllById(projectOrgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), o -> o.getName()));

        Map<Long, ScopeInfo> result = new HashMap<>();
        for (Dashboard d : dashboards) {
            ScopeInfo info = switch (d.getLevel()) {
                case PLATFORM -> new ScopeInfo("Platform", null);
                case ORGANIZATION -> new ScopeInfo(orgNames.getOrDefault(d.getScopeId(), "Unknown organization"), d.getScopeId());
                case PROJECT -> {
                    Project p = projectsById.get(d.getScopeId());
                    if (p == null) yield new ScopeInfo("Unknown project", null);
                    String orgName = projectOrgNames.get(p.getOrganizationId());
                    String label = orgName == null ? p.getName() : orgName + " / " + p.getName();
                    yield new ScopeInfo(label, p.getOrganizationId());
                }
            };
            result.put(d.getId(), info);
        }
        return result;
    }
}
