package com.martecyber.ares.agents.tasks;

import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.GlobalTaskDto;
import com.martecyber.ares.common.PagedResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Cross-project agent task monitoring — powers the global ops view in the Job Queue UI. */
@RestController
@RequestMapping("/api/v1/agent-tasks")
public class GlobalAgentTaskController {

    private final AgentTaskService service;
    private final AgentToolSpecRegistry agentToolSpecRegistry;

    public GlobalAgentTaskController(AgentTaskService service, AgentToolSpecRegistry agentToolSpecRegistry) {
        this.service  = service;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
    }

    @GetMapping("/tools")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AgentToolSpec> tools() {
        return agentToolSpecRegistry.list();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<GlobalTaskDto> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> tool,
            @RequestParam(required = false) Long poolId,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return PagedResponse.of(service.listAll(status, tool, poolId, sortBy, sortDir, page, size), t -> t);
    }

    /** Single-task detail — backs the read-only preview modal opened from the agent pool
     *  timeline's queue table and its load-graph bars. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public GlobalTaskDto get(@PathVariable Long id) {
        return service.getGlobal(id);
    }
}
