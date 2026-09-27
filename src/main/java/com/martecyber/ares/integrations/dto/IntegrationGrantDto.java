package com.martecyber.ares.integrations.dto;

import com.martecyber.ares.integrations.grants.IntegrationGrant;
import java.time.OffsetDateTime;
import java.util.List;

public record IntegrationGrantDto(
    Long id,
    Long integrationId,
    Long organizationId,
    Long projectId,
    List<String> capabilities,
    /** Tenable MSSP only: child container UUID of the managed account. */
    String accountId,
    /** Tenable MSSP only: readable name of the managed account. */
    String accountName,
    /** Greenbone/OpenVAS only: GVM task UUID this grant syncs/launches. */
    String taskId,
    /** Greenbone/OpenVAS only: readable name of the GVM task. */
    String taskName,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static IntegrationGrantDto from(IntegrationGrant g) {
        return new IntegrationGrantDto(
            g.getId(), g.getIntegrationId(), g.getOrganizationId(),
            g.getProjectId(), g.getCapabilities(), g.getAccountId(), g.getAccountName(),
            g.getTaskId(), g.getTaskName(),
            g.isActive(), g.getCreatedAt(), g.getUpdatedAt()
        );
    }
}
