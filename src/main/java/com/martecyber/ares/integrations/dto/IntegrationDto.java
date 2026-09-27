package com.martecyber.ares.integrations.dto;

import com.martecyber.ares.integrations.Integration;
import java.time.OffsetDateTime;

/** Read model — credentials are NEVER included. */
public record IntegrationDto(
    Long id,
    Long toolId,
    String name,
    String type,
    String scope,
    Long organizationId,
    String status,
    String connectionStatus,
    String connectionError,
    OffsetDateTime lastSyncAt,
    String settings,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static IntegrationDto from(Integration i) {
        return new IntegrationDto(
            i.getId(), i.getToolId(), i.getName(), i.getType(),
            i.getScope(), i.getOrganizationId(), i.getStatus(),
            i.getConnectionStatus(), i.getConnectionError(), i.getLastSyncAt(),
            i.getSettings(), i.getCreatedAt(), i.getUpdatedAt()
        );
    }
}
