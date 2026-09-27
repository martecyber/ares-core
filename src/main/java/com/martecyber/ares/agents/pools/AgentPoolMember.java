package com.martecyber.ares.agents.pools;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "agent_pool_member", schema = "ares")
@IdClass(AgentPoolMember.Id.class)
public class AgentPoolMember {

    @jakarta.persistence.Id
    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @jakarta.persistence.Id
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    public AgentPoolMember() {}
    public AgentPoolMember(Long poolId, Long agentId) {
        this.poolId = poolId;
        this.agentId = agentId;
    }

    public Long getPoolId() { return poolId; }
    public void setPoolId(Long v) { this.poolId = v; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long v) { this.agentId = v; }

    /** Composite key class — JPA needs this when @IdClass is used. */
    public static class Id implements Serializable {
        private Long poolId;
        private Long agentId;

        public Id() {}
        public Id(Long poolId, Long agentId) { this.poolId = poolId; this.agentId = agentId; }

        public Long getPoolId() { return poolId; }
        public void setPoolId(Long v) { this.poolId = v; }
        public Long getAgentId() { return agentId; }
        public void setAgentId(Long v) { this.agentId = v; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(poolId, other.poolId) && Objects.equals(agentId, other.agentId);
        }
        @Override public int hashCode() { return Objects.hash(poolId, agentId); }
    }
}
