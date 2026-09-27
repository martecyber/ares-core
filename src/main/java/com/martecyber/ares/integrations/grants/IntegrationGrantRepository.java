package com.martecyber.ares.integrations.grants;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IntegrationGrantRepository extends JpaRepository<IntegrationGrant, Long> {

    List<IntegrationGrant> findByIntegrationId(Long integrationId);

    List<IntegrationGrant> findByOrganizationId(Long organizationId);

    /**
     * Resolves grants accessible from a given project:
     * org-wide grants (projectId IS NULL) + project-specific grants.
     */
    @Query("""
        SELECT g FROM IntegrationGrant g
        WHERE g.active = true
          AND g.organizationId = :orgId
          AND (g.projectId IS NULL OR g.projectId = :engId)
        """)
    List<IntegrationGrant> findActiveForProject(@Param("orgId") Long orgId,
                                                   @Param("engId") Long engId);

    /** Check if a specific integration is accessible from a given project. */
    @Query("""
        SELECT COUNT(g) > 0 FROM IntegrationGrant g
        WHERE g.active = true
          AND g.integrationId = :integrationId
          AND g.organizationId = :orgId
          AND (g.projectId IS NULL OR g.projectId = :engId)
        """)
    boolean existsActiveForProject(@Param("integrationId") Long integrationId,
                                      @Param("orgId") Long orgId,
                                      @Param("engId") Long engId);
}
