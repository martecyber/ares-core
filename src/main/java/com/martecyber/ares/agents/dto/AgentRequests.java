package com.martecyber.ares.agents.dto;

import java.util.List;

/** Compact home for the small request/response records used by Agent controllers. */
public final class AgentRequests {

    private AgentRequests() {}

    public record CreateAgent(String name, String description) {}

    public record UpdateAgent(String name, String description, Boolean enabled,
                              Integer maxConcurrentTasks) {}

    /** Returned on creation — includes the enrollment code that's only shown ONCE. */
    public record CreateAgentResponse(AgentDto agent, String enrollmentCode) {}

    /** Agent → server, called once with the enrollment code. Returns a long-lived token. */
    public record EnrollRequest(
        String code,
        String hostname,
        String platform,
        String arch,
        String version,
        List<CapabilityEntry> capabilities
    ) {}

    public record EnrollResponse(Long agentId, String token, int heartbeatIntervalSeconds) {}

    public record CapabilityEntry(String tool, String path, String version, Boolean canRoot) {}

    /** Reported alongside (never instead of) {@code capabilities} — a tool the platform knows
     *  about (via {@code AgentToolSpecRegistry}) that this agent currently can't run, and why
     *  (missing binary, version too old, needs root, customBuilder module not synced/blocked by
     *  local policy...). Purely advisory, for {@code AgentDetailView} — task dispatch only ever
     *  reads {@code capabilities}, never this list. */
    public record UnavailableToolEntry(String toolId, String reason) {}

    /** Agent → server, periodic. Server uses it to keep last_seen_at fresh + tell agent updates. */
    public record HeartbeatRequest(
        String version,
        List<CapabilityEntry> capabilities,
        List<Long> activeTaskIds,
        /** How many parallel tasks this agent instance was started with. Overrides the stored value. */
        Integer maxConcurrentTasks,
        /** Free bytes on the agent's task workdir filesystem. Null when agent is older than this feature. */
        Long diskFreeBytes,
        /** Null when the agent is older than this feature, or simply has nothing unavailable to report. */
        List<UnavailableToolEntry> unavailableTools
    ) {}

    public record HeartbeatResponse(
        String latestAgentVersion,
        int heartbeatIntervalSeconds,
        boolean tasksAvailable,
        /** {@code AgentToolSpecRegistry#version()} at response time — the agent refetches {@code
         *  GET /agent/tool-specs} only when this differs from what it last cached. */
        long toolSpecsVersion
    ) {}
}
