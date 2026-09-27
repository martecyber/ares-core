package com.martecyber.ares.audit;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    public void log(String action, String resourceType, Long resourceId, Long organizationId) {
        log(action, resourceType, resourceId, organizationId, null, null);
    }

    public void log(String action, String resourceType, Long resourceId,
                    Long organizationId, String oldValue, String newValue) {
        Long actorId = resolveActorId();
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setOrganizationId(organizationId);
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        entry.setCreatedAt(OffsetDateTime.now());
        repo.save(entry);
    }

    private static Long resolveActorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
