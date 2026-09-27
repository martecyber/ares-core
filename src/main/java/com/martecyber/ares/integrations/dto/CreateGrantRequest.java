package com.martecyber.ares.integrations.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateGrantRequest(
    @NotNull Long organizationId,
    /** Null = org-wide. Non-null = restricted to this project. */
    Long projectId,
    /** e.g. ["SYNC_ASSETS", "SYNC_VULNS"] */
    List<String> capabilities,
    /** Tenable MSSP only: child container UUID of the managed account to sync. */
    String accountId,
    /** Tenable MSSP only: readable name of the managed account (stored for display). */
    String accountName,
    /** Greenbone/OpenVAS only: GVM task UUID this grant syncs/launches. */
    String taskId,
    /** Greenbone/OpenVAS only: readable name of the GVM task (stored for display). */
    String taskName
) {}
