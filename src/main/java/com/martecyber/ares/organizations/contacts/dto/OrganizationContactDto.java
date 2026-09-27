package com.martecyber.ares.organizations.contacts.dto;

import com.martecyber.ares.organizations.contacts.OrganizationContact;

import java.time.OffsetDateTime;

public record OrganizationContactDto(
    Long id,
    Long organizationId,
    String name,
    String email,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static OrganizationContactDto from(OrganizationContact c) {
        return new OrganizationContactDto(
            c.getId(), c.getOrganizationId(), c.getName(), c.getEmail(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
