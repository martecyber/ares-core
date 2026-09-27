package com.martecyber.ares.organizations.contacts;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.organizations.contacts.dto.CreateOrganizationContactRequest;
import com.martecyber.ares.organizations.contacts.dto.OrganizationContactDto;
import com.martecyber.ares.organizations.contacts.dto.UpdateOrganizationContactRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class OrganizationContactService {

    private final OrganizationContactRepository repo;

    public OrganizationContactService(OrganizationContactRepository repo) {
        this.repo = repo;
    }

    public List<OrganizationContactDto> list(Long organizationId) {
        return repo.findByOrganizationIdOrderByNameAsc(organizationId).stream()
            .map(OrganizationContactDto::from).toList();
    }

    @Transactional
    public OrganizationContactDto create(Long organizationId, CreateOrganizationContactRequest req) {
        OrganizationContact c = new OrganizationContact();
        c.setOrganizationId(organizationId);
        c.setName(req.name().trim());
        c.setEmail(req.email().trim());
        c.setCreatedAt(OffsetDateTime.now());
        repo.save(c);
        return OrganizationContactDto.from(c);
    }

    @Transactional
    public OrganizationContactDto update(Long organizationId, Long id, UpdateOrganizationContactRequest req) {
        OrganizationContact c = repo.findById(id)
            .filter(x -> x.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> NotFoundException.of("organization_contact", id));
        c.setName(req.name().trim());
        c.setEmail(req.email().trim());
        c.setUpdatedAt(OffsetDateTime.now());
        repo.save(c);
        return OrganizationContactDto.from(c);
    }

    @Transactional
    public void delete(Long organizationId, Long id) {
        OrganizationContact c = repo.findById(id)
            .filter(x -> x.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> NotFoundException.of("organization_contact", id));
        repo.delete(c);
    }
}
