package com.martecyber.ares.agents.dto;

import com.martecyber.ares.agents.Agent;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Public representation of an agent. Agents are now organization-agnostic — they're
 * platform inventory that gets connected to orgs/projects via pool grants.
 *
 * {@code status} is derived from lastSeenAt: online if <90s, stale <5min, offline
 * otherwise (or "pending" when never seen, "disabled" when manually disabled).
 */
public record AgentDto(
    Long id,
    String name,
    String description,
    boolean enabled,
    String hostname,
    String platform,
    String arch,
    String version,
    String capabilities,
    String unavailableTools,
    int maxConcurrentTasks,
    String status,
    OffsetDateTime lastSeenAt,
    OffsetDateTime registeredAt,
    Long registeredByUser,
    boolean enrolled
) {
    public static AgentDto from(Agent a) {
        return new AgentDto(
            a.getId(), a.getName(), a.getDescription(),
            a.isEnabled(), a.getHostname(), a.getPlatform(), a.getArch(), a.getVersion(),
            a.getCapabilities(), a.getUnavailableTools(), a.getMaxConcurrentTasks(),
            computeStatus(a.getLastSeenAt(), a.isEnabled()),
            a.getLastSeenAt(), a.getRegisteredAt(), a.getRegisteredByUser(),
            a.getTokenHash() != null
        );
    }

    private static String computeStatus(OffsetDateTime lastSeen, boolean enabled) {
        if (!enabled) return "disabled";
        if (lastSeen == null) return "pending";
        Duration age = Duration.between(lastSeen, OffsetDateTime.now());
        if (age.toSeconds() < 90)  return "online";
        if (age.toMinutes() < 5)   return "stale";
        return "offline";
    }
}
