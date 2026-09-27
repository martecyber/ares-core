package com.martecyber.ares.organizations.contacts;

import com.martecyber.ares.organizations.contacts.dto.CreateOrganizationContactRequest;
import com.martecyber.ares.organizations.contacts.dto.OrganizationContactDto;
import com.martecyber.ares.organizations.contacts.dto.UpdateOrganizationContactRequest;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Reusable named email contacts per organization (e.g. "Ticketing") — referenced by
 *  "email_recipients" Rules of Engagement to auto-populate a finding report's To/CC/BCC. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/contacts")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
public class OrganizationContactController {

    private final OrganizationContactService service;
    private final OrgScopeService orgScope;

    public OrganizationContactController(OrganizationContactService service, OrgScopeService orgScope) {
        this.service = service;
        this.orgScope = orgScope;
    }

    @GetMapping
    public List<OrganizationContactDto> list(@PathVariable Long orgId, Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        return service.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationContactDto create(@PathVariable Long orgId, @Valid @RequestBody CreateOrganizationContactRequest req,
                                          Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        return service.create(orgId, req);
    }

    @PatchMapping("/{id}")
    public OrganizationContactDto update(@PathVariable Long orgId, @PathVariable Long id,
                                          @Valid @RequestBody UpdateOrganizationContactRequest req, Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        return service.update(orgId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long orgId, @PathVariable Long id, Authentication auth) {
        orgScope.assertOrgAccess(auth, orgId);
        service.delete(orgId, id);
    }
}
