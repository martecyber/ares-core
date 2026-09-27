package com.martecyber.ares.integrations.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessagingIntegrationGrantRepository extends JpaRepository<MessagingIntegrationGrant, Long> {

    List<MessagingIntegrationGrant> findByIntegrationIdOrderByCreatedAtAsc(Long integrationId);

    /** All active grants that authorize an integration for a given (org, project) pair —
     *  matches both project-specific grants and org-wide grants. */
    @Query("""
        SELECT g FROM MessagingIntegrationGrant g
        WHERE g.active = true
          AND g.organizationId = :organizationId
          AND (g.projectId = :projectId OR g.projectId IS NULL)
        """)
    List<MessagingIntegrationGrant> findForProject(@Param("organizationId") Long organizationId,
                                                    @Param("projectId") Long projectId);

    /** Org-scope grants: anything an org has access to (project-specific OR org-wide). */
    @Query("""
        SELECT g FROM MessagingIntegrationGrant g
        WHERE g.active = true AND g.organizationId = :organizationId
        """)
    List<MessagingIntegrationGrant> findForOrganization(@Param("organizationId") Long organizationId);
}
