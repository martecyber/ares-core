package com.martecyber.ares.agents.pools;

import com.martecyber.ares.agents.pools.dto.AgentPoolDtos.PoolDto;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.ProjectRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Project-scoped read API: which pools does this project have access to (via grants)?
 * Used by the project-level task creation UI in Phase 3.
 */
@RestController
@RequestMapping("/api/v1/projects/{engId}/agent-pools")
public class ProjectAgentPoolController {

    private final AgentPoolService poolService;
    private final ProjectRepository projectRepo;

    public ProjectAgentPoolController(AgentPoolService poolService, ProjectRepository projectRepo) {
        this.poolService = poolService;
        this.projectRepo = projectRepo;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<PoolDto> list(@PathVariable Long engId) {
        Long orgId = projectRepo.findById(engId)
            .map(p -> p.getOrganizationId())
            .orElseThrow(() -> NotFoundException.of("project", engId));
        return poolService.listForProject(orgId, engId);
    }
}
