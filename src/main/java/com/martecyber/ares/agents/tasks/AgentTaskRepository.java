package com.martecyber.ares.agents.tasks;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long>, JpaSpecificationExecutor<AgentTask> {

    Page<AgentTask> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    Page<AgentTask> findByAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    List<AgentTask> findByAgentIdAndStatus(Long agentId, String status);

    /** Tasks still in dispatched or running state for a given agent — used on restart to recover in-flight work. */
    List<AgentTask> findByAgentIdAndStatusIn(Long agentId, List<String> statuses);

    /**
     * Picks (and row-locks) the next pending task assigned to any pool this agent belongs to,
     * filtered to only tools the agent has actually probed as available. Uses SKIP LOCKED so
     * concurrent agents grab different rows. The lock is held until the surrounding
     * transaction commits, so the caller can safely UPDATE the same row in the same
     * transaction without losing it to a racing claim.
     *
     * Tasks whose tool isn't available on ANY agent in their pool simply stay pending —
     * caught by {@link #findStalled} for operator visibility.
     *
     * Returns the task id (NULL = nothing pending). Must run inside @Transactional.
     */
    @Query(value = """
        SELECT t.id FROM ares.agent_task t
        JOIN ares.agent_pool_member m ON m.pool_id = t.pool_id
        WHERE m.agent_id = :agentId
          AND t.status = 'pending'
          AND (t.scheduled_for IS NULL OR t.scheduled_for <= :now)
          AND t.tool IN (:tools)
        ORDER BY t.priority DESC, t.created_at
        FOR UPDATE OF t SKIP LOCKED
        LIMIT 1
        """, nativeQuery = true)
    Long pickNextPendingForAgent(@Param("agentId") Long agentId,
                                 @Param("now") OffsetDateTime now,
                                 @Param("tools") Collection<String> tools);

    /** Like pickNextPendingForAgent but claims up to {@code count} rows in one query. */
    @Query(value = """
        SELECT t.id FROM ares.agent_task t
        JOIN ares.agent_pool_member m ON m.pool_id = t.pool_id
        WHERE m.agent_id = :agentId
          AND t.status = 'pending'
          AND (t.scheduled_for IS NULL OR t.scheduled_for <= :now)
          AND t.tool IN (:tools)
        ORDER BY t.priority DESC, t.created_at
        FOR UPDATE OF t SKIP LOCKED
        LIMIT :count
        """, nativeQuery = true)
    List<Long> pickNextNPendingForAgent(@Param("agentId") Long agentId,
                                        @Param("now") OffsetDateTime now,
                                        @Param("tools") Collection<String> tools,
                                        @Param("count") int count);

    /** Does this agent currently belong to a pool with any pending tasks for tools it can run? */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM ares.agent_task t
            JOIN ares.agent_pool_member m ON m.pool_id = t.pool_id
            WHERE m.agent_id = :agentId
              AND t.status = 'pending'
              AND (t.scheduled_for IS NULL OR t.scheduled_for <= :now)
              AND t.tool IN (:tools)
        )
        """, nativeQuery = true)
    boolean hasPendingForAgent(@Param("agentId") Long agentId,
                               @Param("now") OffsetDateTime now,
                               @Param("tools") Collection<String> tools);

    Optional<AgentTask> findByIdAndAgentId(Long id, Long agentId);

    /** Tasks stuck in an active state since before the given cutoff — for stall detection. */
    @Query("SELECT t FROM AgentTask t WHERE t.status IN ('pending','dispatched','running') AND t.createdAt < :cutoff")
    java.util.List<AgentTask> findStalled(@org.springframework.data.repository.query.Param("cutoff") java.time.OffsetDateTime cutoff);

    /** Ids of tasks still actively running past their own configured timeout_minutes
     *  (measured from started_at, falling back to dispatched_at). Tasks with no
     *  timeout_minutes set (NULL) never match — that's how the timeout is disabled. */
    @Query(value = """
        SELECT id FROM ares.agent_task
        WHERE status IN ('dispatched', 'running', 'uploading')
          AND timeout_minutes IS NOT NULL
          AND COALESCE(started_at, dispatched_at) + make_interval(mins => timeout_minutes) < NOW()
        """, nativeQuery = true)
    List<Long> findTimedOutTaskIds();

    /**
     * Resets dispatched/running tasks assigned to agents whose last heartbeat was older than
     * {@code cutoff} back to {@code pending} so another agent can pick them up.
     * Returns the number of rows updated.
     */
    @Modifying
    @Query(value = """
        UPDATE ares.agent_task
        SET status = 'pending', agent_id = NULL, dispatched_at = NULL
        WHERE status IN ('dispatched', 'running')
          AND agent_id IN (
            SELECT id FROM ares.agent
            WHERE last_seen_at IS NULL
               OR last_seen_at < :cutoff
          )
        """, nativeQuery = true)
    int resetOrphanedByOfflineAgents(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Resets dispatched/running tasks for an agent dispatched before the grace cutoff.
     * Used when agent reports no active tasks — the cutoff prevents resetting tasks
     * that were very recently dispatched and whose /fail call may still be in-flight.
     */
    @Modifying
    @Query(value = """
        UPDATE ares.agent_task
        SET status = 'pending', agent_id = NULL, dispatched_at = NULL
        WHERE agent_id = :agentId
          AND status IN ('dispatched', 'running')
          AND dispatched_at < :graceCutoff
        """, nativeQuery = true)
    int resetAllDroppedByAgent(@Param("agentId") Long agentId,
                               @Param("graceCutoff") OffsetDateTime graceCutoff);

    /** Resets dispatched/running tasks for an agent that are NOT in the given active id set. */
    @Modifying
    @Query(value = """
        UPDATE ares.agent_task
        SET status = 'pending', agent_id = NULL, dispatched_at = NULL
        WHERE agent_id = :agentId
          AND status IN ('dispatched', 'running')
          AND id NOT IN (:activeIds)
        """, nativeQuery = true)
    int resetDroppedByAgent(@Param("agentId") Long agentId,
                            @Param("activeIds") Collection<Long> activeIds);

    /**
     * Pool timeline support: tasks of this pool whose execution interval
     * overlaps the window [from, to], plus any still-active task regardless of
     * window so the timeline can still render its "now" bar.
     */
    @Query(value = """
        SELECT * FROM ares.agent_task
        WHERE pool_id = :poolId
          AND (
              (started_at IS NOT NULL AND started_at < :toTs
                AND (completed_at IS NULL OR completed_at >= :fromTs))
              OR status IN ('dispatched', 'running', 'uploading')
          )
        """, nativeQuery = true)
    List<AgentTask> findActiveInWindowForPool(@Param("poolId") Long poolId,
                                              @Param("fromTs") OffsetDateTime fromTs,
                                              @Param("toTs") OffsetDateTime toTs);

    /**
     * Tasks that could have been sitting in this pool's queue at any moment
     * during the window. Filters out tasks that had already left the queue
     * (dispatched or cancelled-before-dispatch) before the window starts.
     * The caller bucketizes by sample timestamp in code — cheaper than a
     * generate_series subquery and avoids hammering the planner.
     */
    @Query(value = """
        SELECT * FROM ares.agent_task
        WHERE pool_id = :poolId
          AND created_at < :windowEnd
          AND (
               (dispatched_at IS NULL AND completed_at IS NULL)
            OR (dispatched_at IS NOT NULL AND dispatched_at > :windowStart)
            OR (status = 'cancelled' AND dispatched_at IS NULL
                AND completed_at IS NOT NULL AND completed_at > :windowStart)
          )
        """, nativeQuery = true)
    List<AgentTask> findQueueOverlapForPool(@Param("poolId") Long poolId,
                                             @Param("windowStart") OffsetDateTime windowStart,
                                             @Param("windowEnd") OffsetDateTime windowEnd);

    /** Count of pending tasks in this pool that an agent could claim right now. */
    @Query(value = """
        SELECT COUNT(*) FROM ares.agent_task
        WHERE pool_id = :poolId
          AND status = 'pending'
          AND (scheduled_for IS NULL OR scheduled_for <= :now)
        """, nativeQuery = true)
    long countClaimablePendingForPool(@Param("poolId") Long poolId,
                                       @Param("now") OffsetDateTime now);

    /** Pending tasks for this pool whose scheduled_for falls in the future window — for timeline forecast markers. */
    @Query(value = """
        SELECT * FROM ares.agent_task
        WHERE pool_id = :poolId
          AND status = 'pending'
          AND scheduled_for IS NOT NULL
          AND scheduled_for > :from
          AND scheduled_for <= :to
        ORDER BY scheduled_for
        """, nativeQuery = true)
    List<AgentTask> findScheduledInFutureForPool(@Param("poolId") Long poolId,
                                                 @Param("from") OffsetDateTime from,
                                                 @Param("to") OffsetDateTime to);

    /** Returns {min(started_at), max(completed_at)} across every task of a schedule run — for total run duration. */
    @Query(value = """
        SELECT MIN(started_at), MAX(completed_at)
        FROM ares.agent_task
        WHERE schedule_run_id = :runId
        """, nativeQuery = true)
    Object[] timestampRangeForRun(@Param("runId") Long runId);

    @Modifying
    @Query("DELETE FROM AgentTask t WHERE t.createdAt < :cutoff AND t.status IN ('completed', 'failed', 'cancelled')")
    int deleteTerminalOlderThan(@Param("cutoff") OffsetDateTime cutoff);

}
