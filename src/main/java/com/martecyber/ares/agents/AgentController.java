package com.martecyber.ares.agents;

import com.martecyber.ares.agents.dto.AgentDto;
import com.martecyber.ares.agents.dto.AgentRequests.*;
import com.martecyber.ares.agents.pools.AgentPool;
import com.martecyber.ares.agents.pools.AgentPoolMemberRepository;
import com.martecyber.ares.agents.pools.AgentPoolRepository;
import com.martecyber.ares.agents.pools.dto.AgentPoolDtos.PoolDto;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.TaskDto;
import com.martecyber.ares.common.PagedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for managing agents in an org's inventory. Agent-originated traffic
 * (enrollment, heartbeat, …) lives in {@link AgentRuntimeController} under /api/v1/agent.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService service;
    private final AgentTaskService taskService;
    private final AgentPoolMemberRepository memberRepo;
    private final AgentPoolRepository poolRepo;

    public AgentController(AgentService service,
                           AgentTaskService taskService,
                           AgentPoolMemberRepository memberRepo,
                           AgentPoolRepository poolRepo) {
        this.service = service;
        this.taskService = taskService;
        this.memberRepo = memberRepo;
        this.poolRepo = poolRepo;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<AgentDto> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(page, size), a -> a);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AgentDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<CreateAgentResponse> create(@RequestBody CreateAgent req, Authentication auth) {
        Long createdBy = parseUserId(auth);
        CreateAgentResponse out = service.create(req, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AgentDto update(@PathVariable Long id, @RequestBody UpdateAgent req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Pools this agent is a member of. Powers the detail view. */
    @GetMapping("/{id}/pools")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<PoolDto> poolsFor(@PathVariable Long id) {
        service.get(id); // 404 if absent
        var memberships = memberRepo.findByAgentId(id);
        if (memberships.isEmpty()) return List.of();
        var poolIds = memberships.stream().map(m -> m.getPoolId()).toList();
        // memberCount is the bigger number — but we only have one agent here, so each row's
        // own pool's memberCount is irrelevant for the detail view. Pass 0 to skip the extra
        // query and keep this fast.
        return poolRepo.findAllById(poolIds).stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(p -> PoolDto.from(p, 0))
            .toList();
    }

    /** Recent tasks (any status) executed by this agent. */
    @GetMapping("/{id}/tasks")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<TaskDto> tasksFor(@PathVariable Long id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        service.get(id); // 404 if absent
        return PagedResponse.of(taskService.listForAgent(id, page, size), t -> t);
    }

    private static Long parseUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
