package com.martecyber.ares.organizations.contacts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateOrganizationContactRequest(
    @NotBlank String name,
    @NotBlank @Email String email
) {}
