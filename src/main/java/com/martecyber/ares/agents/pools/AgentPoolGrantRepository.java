package com.martecyber.ares.agents.pools;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentPoolGrantRepository extends JpaRepository<AgentPoolGrant, Long> {

    List<AgentPoolGrant> findByAgentPoolId(Long agentPoolId);

    /**
     * Returns all pools the project has access to — either via a project-specific grant
     * or via an org-wide grant for the project's owning org.
     */
    @Query("""
        SELECT g FROM AgentPoolGrant g
        WHERE g.active = true
          AND g.organizationId = :organizationId
          AND (g.projectId = :projectId OR g.projectId IS NULL)
        """)
    List<AgentPoolGrant> findForProject(@Param("organizationId") Long organizationId,
                                         @Param("projectId") Long projectId);

    boolean existsByAgentPoolIdAndOrganizationIdAndProjectIdAndActiveTrue(
        Long agentPoolId, Long organizationId, Long projectId);
}
