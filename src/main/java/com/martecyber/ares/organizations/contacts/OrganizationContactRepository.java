package com.martecyber.ares.organizations.contacts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganizationContactRepository extends JpaRepository<OrganizationContact, Long> {
    List<OrganizationContact> findByOrganizationIdOrderByNameAsc(Long organizationId);
    List<OrganizationContact> findByIdInAndOrganizationId(List<Long> ids, Long organizationId);
}
