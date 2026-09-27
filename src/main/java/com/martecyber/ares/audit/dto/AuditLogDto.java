package com.martecyber.ares.audit.dto;

import com.martecyber.ares.audit.AuditLog;
import java.time.OffsetDateTime;

public record AuditLogDto(
    Long id,
    Long actorId,
    Long organizationId,
    String action,
    String resourceType,
    Long resourceId,
    String oldValue,
    String newValue,
    String ipAddress,
    OffsetDateTime createdAt
) {
    public static AuditLogDto from(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getActorId(), a.getOrganizationId(),
            a.getAction(), a.getResourceType(), a.getResourceId(),
            a.getOldValue(), a.getNewValue(), a.getIpAddress(), a.getCreatedAt());
    }
}
