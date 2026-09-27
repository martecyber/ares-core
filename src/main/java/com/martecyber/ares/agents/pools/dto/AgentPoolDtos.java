package com.martecyber.ares.agents.pools.dto;

import com.martecyber.ares.agents.pools.AgentPool;
import com.martecyber.ares.agents.pools.AgentPoolGrant;

import java.time.OffsetDateTime;
import java.util.List;

public final class AgentPoolDtos {

    private AgentPoolDtos() {}

    public record PoolDto(
        Long id, String name, String description,
        boolean enabled, int memberCount, OffsetDateTime createdAt
    ) {
        public static PoolDto from(AgentPool p, int memberCount) {
            return new PoolDto(p.getId(), p.getName(),
                p.getDescription(), p.isEnabled(), memberCount, p.getCreatedAt());
        }
    }

    public record CreatePool(String name, String description) {}
    public record UpdatePool(String name, String description, Boolean enabled) {}

    public record SetMembers(List<Long> agentIds) {}

    public record GrantDto(
        Long id, Long agentPoolId, Long organizationId, Long projectId,
        boolean active, OffsetDateTime createdAt
    ) {
        public static GrantDto from(AgentPoolGrant g) {
            return new GrantDto(g.getId(), g.getAgentPoolId(), g.getOrganizationId(),
                g.getProjectId(), g.isActive(), g.getCreatedAt());
        }
    }

    public record CreateGrant(Long organizationId, Long projectId) {}
}
