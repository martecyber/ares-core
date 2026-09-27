package com.martecyber.ares.agents.pools;

import com.martecyber.ares.agents.Agent;
import com.martecyber.ares.agents.AgentRepository;
import com.martecyber.ares.agents.pools.dto.AgentPoolDtos.*;
import com.martecyber.ares.agents.pools.dto.PoolTimelineDto;
import com.martecyber.ares.agents.tasks.AgentTask;
import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AgentPoolService {

    private final AgentPoolRepository poolRepo;
    private final AgentPoolMemberRepository memberRepo;
    private final AgentPoolGrantRepository grantRepo;
    private final AgentRepository agentRepo;
    private final AgentTaskRepository taskRepo;

    public AgentPoolService(AgentPoolRepository poolRepo,
                            AgentPoolMemberRepository memberRepo,
                            AgentPoolGrantRepository grantRepo,
                            AgentRepository agentRepo,
                            AgentTaskRepository taskRepo) {
        this.poolRepo = poolRepo;
        this.memberRepo = memberRepo;
        this.grantRepo = grantRepo;
        this.agentRepo = agentRepo;
        this.taskRepo = taskRepo;
    }

    public List<PoolDto> list() {
        return poolRepo.findAllByOrderByNameAsc().stream()
            .map(p -> PoolDto.from(p, memberRepo.findByPoolId(p.getId()).size()))
            .toList();
    }

    public PoolDto get(Long id) {
        AgentPool p = load(id);
        return PoolDto.from(p, memberRepo.findByPoolId(id).size());
    }

    @Transactional
    public PoolDto create(CreatePool req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        AgentPool p = new AgentPool();
        p.setName(req.name().trim());
        p.setDescription(req.description());
        p.setCreatedAt(OffsetDateTime.now());
        AgentPool saved = poolRepo.save(p);
        return PoolDto.from(saved, 0);
    }

    @Transactional
    public PoolDto update(Long id, UpdatePool req) {
        AgentPool p = load(id);
        if (req.name() != null && !req.name().isBlank()) p.setName(req.name().trim());
        if (req.description() != null) p.setDescription(req.description());
        if (req.enabled() != null) p.setEnabled(req.enabled());
        AgentPool saved = poolRepo.save(p);
        return PoolDto.from(saved, memberRepo.findByPoolId(id).size());
    }

    @Transactional
    public void delete(Long id) {
        if (!poolRepo.existsById(id)) throw NotFoundException.of("agent pool", id);
        poolRepo.deleteById(id);  // cascades to members + grants via FK
    }

    // ── Members ────────────────────────────────────────────────────────────

    public List<Long> listMembers(Long poolId) {
        return memberRepo.findByPoolId(poolId).stream().map(AgentPoolMember::getAgentId).toList();
    }

    /** Idempotent replacement: members not in `agentIds` are removed; new ones added. */
    @Transactional
    public void setMembers(Long poolId, List<Long> agentIds) {
        if (!poolRepo.existsById(poolId)) throw NotFoundException.of("agent pool", poolId);
        Set<Long> desired = new HashSet<>(agentIds == null ? List.of() : agentIds);
        Set<Long> current = new HashSet<>(listMembers(poolId));
        for (Long add : desired) {
            if (!current.contains(add)) memberRepo.save(new AgentPoolMember(poolId, add));
        }
        for (Long remove : current) {
            if (!desired.contains(remove)) memberRepo.deleteByPoolIdAndAgentId(poolId, remove);
        }
    }

    // ── Grants ─────────────────────────────────────────────────────────────

    public List<GrantDto> listGrants(Long poolId) {
        return grantRepo.findByAgentPoolId(poolId).stream().map(GrantDto::from).toList();
    }

    @Transactional
    public GrantDto createGrant(Long poolId, CreateGrant req) {
        load(poolId); // 404 if pool doesn't exist
        if (req.organizationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId is required");
        }
        // Pools are platform-level; any org can be granted access. The unique constraint on
        // (pool, org, project) plus the partial-NULL index from V64 prevents duplicates.
        AgentPoolGrant g = new AgentPoolGrant();
        g.setAgentPoolId(poolId);
        g.setOrganizationId(req.organizationId());
        g.setProjectId(req.projectId());
        g.setCreatedAt(OffsetDateTime.now());
        return GrantDto.from(grantRepo.save(g));
    }

    @Transactional
    public void revokeGrant(Long grantId) {
        if (!grantRepo.existsById(grantId)) throw NotFoundException.of("agent pool grant", grantId);
        grantRepo.deleteById(grantId);
    }

    /** Pools that a project has access to (project-specific OR org-wide grants). */
    public List<PoolDto> listForProject(Long organizationId, Long projectId) {
        var grants = grantRepo.findForProject(organizationId, projectId);
        Set<Long> ids = new HashSet<>();
        for (var g : grants) ids.add(g.getAgentPoolId());
        return poolRepo.findAllById(ids).stream()
            .filter(AgentPool::isEnabled)
            .map(p -> PoolDto.from(p, memberRepo.findByPoolId(p.getId()).size()))
            .toList();
    }

    /** True if the project has an active grant for the given pool. */
    public boolean isGrantedToProject(Long poolId, Long organizationId, Long projectId) {
        return grantRepo.findForProject(organizationId, projectId).stream()
            .anyMatch(g -> g.getAgentPoolId().equals(poolId));
    }

    private AgentPool load(Long id) {
        return poolRepo.findById(id).orElseThrow(() -> NotFoundException.of("agent pool", id));
    }

    // ── Timeline ───────────────────────────────────────────────────────────

    /**
     * Last {@code hours} of agent activity in this pool: agents + tasks that
     * intersect the window + still-active tasks regardless of window + the
     * current claimable-pending queue size. The frontend renders a Gantt-style
     * view with one row per agent slot.
     */
    @Transactional
    public PoolTimelineDto getTimeline(Long poolId, int hours) {
        AgentPool pool = load(poolId);
        OffsetDateTime now = OffsetDateTime.now();
        // Snap to the current full hour so ticks, bars, and labels all land on HH:00.
        OffsetDateTime nowHour = now.withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime windowStart = nowHour.minusHours(Math.max(1, hours));
        // Extend 6 clean hours ahead for the forecast zone.
        OffsetDateTime windowEnd = nowHour.plusHours(6);
        // Keep the real "now" so the frontend can draw the divider at the exact current second.

        List<Long> memberIds = memberRepo.findByPoolId(poolId).stream()
            .map(AgentPoolMember::getAgentId).toList();

        List<Agent> members = memberIds.isEmpty()
            ? List.of() : agentRepo.findAllById(memberIds);
        List<PoolTimelineDto.AgentEntry> agentEntries = members.stream()
            .map(a -> new PoolTimelineDto.AgentEntry(
                a.getId(), a.getName(), a.getMaxConcurrentTasks(),
                computeStatus(a.getLastSeenAt(), a.isEnabled()),
                a.getLastSeenAt()))
            .toList();

        // Historical + currently-active tasks (no data past now).
        List<AgentTask> raw = taskRepo.findActiveInWindowForPool(poolId, windowStart, now);
        // Pending tasks scheduled to run in the forecast zone — shown as markers.
        List<AgentTask> future = taskRepo.findScheduledInFutureForPool(poolId, now, windowEnd);

        java.util.function.Function<AgentTask, PoolTimelineDto.TaskEntry> toEntry = t ->
            new PoolTimelineDto.TaskEntry(
                t.getId(), t.getAgentId(), t.getName(), t.getTool(), t.getStatus(),
                t.getStartedAt(), t.getCompletedAt(), t.getDispatchedAt(),
                t.getScheduledFor(), t.getScheduleRunId());

        List<PoolTimelineDto.TaskEntry> taskEntries = new java.util.ArrayList<>();
        raw.stream().map(toEntry).forEach(taskEntries::add);
        future.stream().map(toEntry).forEach(taskEntries::add);

        long queueSize = taskRepo.countClaimablePendingForPool(poolId, now);

        // Queue samples cover the clean hourly zone (windowStart → nowHour). The partial
        // current hour is omitted — an incomplete bar would be misleading.
        List<PoolTimelineDto.QueueSample> queueSamples =
            computeQueueSamples(poolId, windowStart, nowHour);

        return new PoolTimelineDto(pool.getId(), pool.getName(),
            windowStart, windowEnd, now, agentEntries, taskEntries, queueSize, queueSamples);
    }

    /**
     * For each of 25 evenly-spaced samples across the window, count how many
     * tasks were sitting in this pool's queue at that instant. A task is in
     * the queue at {@code ts} when it has been created, isn't scheduled in the
     * future, hasn't been dispatched yet, and hasn't been cancelled before
     * being dispatched. One DB roundtrip + O(samples × tasks) in memory —
     * fine for the typical handful of tasks per pool over 24h.
     */
    private List<PoolTimelineDto.QueueSample> computeQueueSamples(
            Long poolId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        final int n = 19; // 19 boundary points → 18 one-hour bars aligned with the hour gridlines
        long startMs = windowStart.toInstant().toEpochMilli();
        long endMs   = windowEnd.toInstant().toEpochMilli();
        long stepMs  = (endMs - startMs) / (n - 1);

        var rows = taskRepo.findQueueOverlapForPool(poolId, windowStart, windowEnd);

        List<PoolTimelineDto.QueueSample> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long sampleMs = startMs + stepMs * i;
            OffsetDateTime ts = java.time.Instant.ofEpochMilli(sampleMs)
                .atOffset(windowStart.getOffset());
            long count = 0;
            for (var row : rows) {
                if (row.getCreatedAt() == null || row.getCreatedAt().toInstant().toEpochMilli() > sampleMs) continue;
                if (row.getScheduledFor() != null
                    && row.getScheduledFor().toInstant().toEpochMilli() > sampleMs) continue;
                Long leftQueueMs = null;
                if (row.getDispatchedAt() != null) {
                    leftQueueMs = row.getDispatchedAt().toInstant().toEpochMilli();
                } else if ("cancelled".equals(row.getStatus()) && row.getCompletedAt() != null) {
                    // Cancelled before being claimed: cancel() stamps completed_at.
                    leftQueueMs = row.getCompletedAt().toInstant().toEpochMilli();
                }
                if (leftQueueMs != null && leftQueueMs <= sampleMs) continue;
                count++;
            }
            out.add(new PoolTimelineDto.QueueSample(ts, count));
        }
        return out;
    }

    /** Same vocabulary as {@code AgentDto.computeStatus} — kept in sync intentionally. */
    private static String computeStatus(OffsetDateTime lastSeen, boolean enabled) {
        if (!enabled) return "disabled";
        if (lastSeen == null) return "pending";
        Duration age = Duration.between(lastSeen, OffsetDateTime.now());
        if (age.toSeconds() < 90)  return "online";
        if (age.toMinutes() < 5)   return "stale";
        return "offline";
    }
}
