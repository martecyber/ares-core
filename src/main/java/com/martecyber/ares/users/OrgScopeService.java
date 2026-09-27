package com.martecyber.ares.users;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.projects.ProjectRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Centralized "does this user have access to organization X" check, reused across every
 * org-scoped controller. MSSP_ADMIN is always unrestricted; every other role (including the
 * new CLIENT_USER) is limited to organizations where a {@link UserRole} row exists for them —
 * the same table that already backs the org-operator assignment UI, just actually enforced now.
 */
@Service
public class OrgScopeService {

    private final UserRoleRepository userRoles;
    private final ProjectRepository projects;
    private final FindingRepository findings;
    private final AffectionRepository affections;

    public OrgScopeService(UserRoleRepository userRoles, ProjectRepository projects,
                            FindingRepository findings, AffectionRepository affections) {
        this.userRoles = userRoles;
        this.projects = projects;
        this.findings = findings;
        this.affections = affections;
    }

    public boolean isPlatformAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_MSSP_ADMIN".equals(a.getAuthority()));
    }

    private Long userId(Authentication auth) {
        return Long.parseLong(auth.getName());
    }

    /** Null means unrestricted (platform admin). */
    public Set<Long> accessibleOrgIds(Authentication auth) {
        if (isPlatformAdmin(auth)) return null;
        return userRoles.findDistinctOrganizationIdsByUserId(userId(auth)).stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    public void assertOrgAccess(Authentication auth, Long orgId) {
        if (isPlatformAdmin(auth)) return;
        Set<Long> accessible = accessibleOrgIds(auth);
        if (orgId == null || accessible == null || !accessible.contains(orgId)) {
            throw new AccessDeniedException("No access to organization " + orgId);
        }
    }

    public void assertProjectAccess(Authentication auth, Long projectId) {
        if (isPlatformAdmin(auth)) return;
        Long orgId = projects.findOrganizationIdById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));
        assertOrgAccess(auth, orgId);
    }

    public void assertFindingAccess(Authentication auth, Long findingId) {
        if (isPlatformAdmin(auth)) return;
        Finding f = findings.findById(findingId)
            .orElseThrow(() -> NotFoundException.of("finding", findingId));
        assertProjectAccess(auth, f.getProjectId());
    }

    public void assertAffectionAccess(Authentication auth, Long affectionId) {
        if (isPlatformAdmin(auth)) return;
        Affection a = affections.findById(affectionId)
            .orElseThrow(() -> NotFoundException.of("affection", affectionId));
        assertFindingAccess(auth, a.getFindingId());
    }
}
