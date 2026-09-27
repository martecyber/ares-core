package com.martecyber.ares.agents.pools;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentPoolMemberRepository extends JpaRepository<AgentPoolMember, AgentPoolMember.Id> {

    List<AgentPoolMember> findByPoolId(Long poolId);

    List<AgentPoolMember> findByAgentId(Long agentId);

    @Modifying
    @Query("DELETE FROM AgentPoolMember m WHERE m.poolId = :poolId")
    void deleteByPoolId(@Param("poolId") Long poolId);

    @Modifying
    @Query("DELETE FROM AgentPoolMember m WHERE m.poolId = :poolId AND m.agentId = :agentId")
    void deleteByPoolIdAndAgentId(@Param("poolId") Long poolId, @Param("agentId") Long agentId);

    boolean existsByPoolIdAndAgentId(Long poolId, Long agentId);
}
