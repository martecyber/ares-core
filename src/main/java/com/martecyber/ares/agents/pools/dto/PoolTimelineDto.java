package com.martecyber.ares.agents.pools.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Single-payload response for {@code GET /agent-pools/{id}/timeline}. The
 * frontend renders a Gantt-style view: a historical zone (windowStart → nowTs)
 * + a 6-hour forecast zone (nowTs → windowEnd). Task bars only appear in the
 * historical zone; the forecast zone is shaded and empty of bars.
 */
public record PoolTimelineDto(
    Long poolId,
    String poolName,
    OffsetDateTime windowStart,
    OffsetDateTime windowEnd,
    /** Boundary between history and forecast — always the server's "now" at response time. */
    OffsetDateTime nowTs,
    List<AgentEntry> agents,
    List<TaskEntry> tasks,
    long queueSize,
    /**
     * Queue-depth samples from windowStart to nowTs (not windowEnd). The UI
     * renders these as a histogram in the historical zone only.
     */
    List<QueueSample> queueSamples
) {
    public record AgentEntry(
        Long id,
        String name,
        int maxConcurrentTasks,
        /** "online" | "stale" | "offline" | "disabled" | "pending" — same vocabulary as AgentDto. */
        String status,
        OffsetDateTime lastSeenAt
    ) {}

    public record QueueSample(OffsetDateTime ts, long count) {}

    public record TaskEntry(
        Long id,
        Long agentId,
        String name,
        String tool,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime dispatchedAt,
        OffsetDateTime scheduledFor,
        Long scheduleRunId
    ) {}
}
