package com.martecyber.ares.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
        SELECT a FROM AuditLog a WHERE
            (:orgId        IS NULL OR a.organizationId = :orgId)        AND
            (:actorId      IS NULL OR a.actorId        = :actorId)      AND
            (:action       IS NULL OR a.action         = :action)       AND
            (:resourceType IS NULL OR a.resourceType   = :resourceType)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLog> filter(
        @Param("orgId")        Long organizationId,
        @Param("actorId")      Long actorId,
        @Param("action")       String action,
        @Param("resourceType") String resourceType,
        Pageable pageable
    );
}
