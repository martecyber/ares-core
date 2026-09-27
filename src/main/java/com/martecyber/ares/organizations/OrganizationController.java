package com.martecyber.ares.organizations;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.organizations.dto.CreateOrganizationRequest;
import com.martecyber.ares.organizations.dto.OrgDashboardDto;
import com.martecyber.ares.organizations.dto.OrganizationDto;
import com.martecyber.ares.organizations.dto.UpdateOrganizationRequest;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.RoleRepository;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.users.UserRoleId;
import com.martecyber.ares.users.UserRoleRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService service;
    private final UserRoleRepository userRoleRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final OrgScopeService orgScope;

    public OrganizationController(OrganizationService service, UserRoleRepository userRoleRepo,
                                   UserRepository userRepo, RoleRepository roleRepo, OrgScopeService orgScope) {
        this.service = service;
        this.userRoleRepo = userRoleRepo;
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.orgScope = orgScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<OrganizationDto> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        Authentication auth
    ) {
        boolean isAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_MSSP_ADMIN".equals(a.getAuthority()));
        Long userId = isAdmin ? null : Long.parseLong(auth.getName());
        return PagedResponse.of(service.list(status, page, size, userId), o -> o);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public OrganizationDto get(@PathVariable Long id, Authentication auth) {
        orgScope.assertOrgAccess(auth, id);
        return service.get(id);
    }

    @GetMapping("/{id}/dashboard")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public OrgDashboardDto dashboard(@PathVariable Long id, Authentication auth) {
        orgScope.assertOrgAccess(auth, id);
        return service.getDashboard(id);
    }

    /**
     * Returns the current user's role in the given organization, or null if they have none.
     * MSSP_ADMINs always have implicit access (handled client-side).
     */
    @GetMapping("/{id}/my-role")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public java.util.Map<String, Object> myOrgRole(@PathVariable Long id, Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        var roles = userRoleRepo.findByUserIdAndOrganizationId(userId, id);
        String roleCode = roles.stream()
            .map(ur -> ur.getRole() != null ? ur.getRole().getCode() : null)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
        return java.util.Map.of("role", roleCode != null ? roleCode : "");
    }

    /** List operators assigned to this organization. */
    @GetMapping("/{id}/operators")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public java.util.List<java.util.Map<String, Object>> listOperators(@PathVariable Long id) {
        return userRoleRepo.findByOrganizationId(id).stream()
            .map(ur -> {
                var user = userRepo.findById(ur.getUserId()).orElse(null);
                var role = ur.getRole();
                return java.util.Map.<String, Object>of(
                    "userId",      ur.getUserId(),
                    "roleId",      ur.getRoleId(),
                    "roleCode",    role != null ? role.getCode() : "",
                    "roleName",    role != null ? role.getName() : "",
                    "email",       user != null ? user.getEmail() : "",
                    "displayName", user != null && user.getDisplayName() != null ? user.getDisplayName() : ""
                );
            })
            .toList();
    }

    /** Assign an operator role to a user for this organization. */
    @PostMapping("/{id}/operators")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void addOperator(@PathVariable Long id,
                            @RequestBody java.util.Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        String roleCode = body.getOrDefault("roleCode", "MSSP_OPERATOR").toString();
        com.martecyber.ares.users.Role role = roleRepo.findByCode(roleCode)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
        userRepo.findById(userId).orElseThrow(() ->
            com.martecyber.ares.common.NotFoundException.of("user", userId));
        var key = new UserRoleId(userId, role.getId(), id);
        if (!userRoleRepo.existsById(key)) {
            var ur = new com.martecyber.ares.users.UserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            ur.setOrganizationId(id);
            userRoleRepo.save(ur);
        }
    }

    /** Remove an operator's role from this organization. */
    @DeleteMapping("/{id}/operators/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void removeOperator(@PathVariable Long id, @PathVariable Long userId,
                               @RequestParam(defaultValue = "MSSP_OPERATOR") String roleCode) {
        com.martecyber.ares.users.Role role = roleRepo.findByCode(roleCode)
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleCode));
        userRoleRepo.deleteByUserIdAndRoleIdAndOrganizationId(userId, role.getId(), id);
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<OrganizationDto> create(@Valid @RequestBody CreateOrganizationRequest req) {
        OrganizationDto created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + created.id())).body(created);
    }

    /**
     * MSSP_ADMIN can update anything (including status — archiving). CLIENT_ADMIN can only
     * touch their own organization's name/SLA — {@link OrganizationService#updateProfile}
     * silently ignores status/settings even if supplied, so archiving is impossible either way.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','CLIENT_ADMIN')")
    public OrganizationDto update(@PathVariable Long id, @Valid @RequestBody UpdateOrganizationRequest req, Authentication auth) {
        if (orgScope.isPlatformAdmin(auth)) {
            return service.update(id, req);
        }
        orgScope.assertOrgAccess(auth, id);
        return service.updateProfile(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','CLIENT_ADMIN')")
    public void uploadLogo(@PathVariable Long id, @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        orgScope.assertOrgAccess(auth, id);
        service.uploadLogo(id, file);
    }

    @DeleteMapping("/{id}/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','CLIENT_ADMIN')")
    public void deleteLogo(@PathVariable Long id, Authentication auth) {
        orgScope.assertOrgAccess(auth, id);
        service.deleteLogo(id);
    }

    @GetMapping("/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable Long id) {
        OrganizationService.LogoData logo = service.getLogo(id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(logo.mime()))
            .header("Cache-Control", "public, max-age=86400")
            .body(logo.data());
    }
}
