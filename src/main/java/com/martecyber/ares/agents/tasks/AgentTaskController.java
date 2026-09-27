package com.martecyber.ares.agents.tasks;

import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.*;
import com.martecyber.ares.common.PagedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Project-scoped task management: list, create, cancel. Tool catalog discovery. */
@RestController
@RequestMapping("/api/v1/projects/{engId}/agent-tasks")
public class AgentTaskController {

    private final AgentTaskService service;
    private final AgentToolSpecRegistry agentToolSpecRegistry;
    private final TargetResolver resolver;

    public AgentTaskController(AgentTaskService service, AgentToolSpecRegistry agentToolSpecRegistry, TargetResolver resolver) {
        this.service = service;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
        this.resolver = resolver;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<TaskDto> list(@PathVariable Long engId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return PagedResponse.of(service.listForProject(engId, page, size), t -> t);
    }

    @GetMapping("/tools")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AgentToolSpec> tools() { return agentToolSpecRegistry.list(); }

    /**
     * Preview the targets a selector would resolve to for this project, right now.
     * Used by the task form dialog to show a live "N targets" count and to populate
     * the exclude-picker list — so the cap is high enough for the operator to see and
     * curate the full set, not just a teaser sample.
     */
    @PostMapping("/preview-targets")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public java.util.Map<String, Object> previewTargets(@PathVariable Long engId,
                                                         @RequestBody PreviewRequest req) {
        if (req == null || req.targetsFrom() == null) {
            return java.util.Map.of("count", 0, "sample", List.of(), "items", List.of());
        }
        List<String> resolved = resolver.resolveUnlimited(engId, req.targetsFrom());
        int cap = Math.min(resolved.size(), 1000);
        List<String> items = resolved.subList(0, cap);
        return java.util.Map.of(
            "count",  resolved.size(),
            "items",  items,
            // Kept for backward compat with any older UI; new code uses `items`.
            "sample", resolved.size() > 20 ? resolved.subList(0, 20) : resolved
        );
    }

    public record PreviewRequest(java.util.Map<String, Object> targetsFrom) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<List<TaskDto>> create(@PathVariable Long engId,
                                                 @RequestBody CreateTask req,
                                                 Authentication auth) {
        Long createdBy = parseUserId(auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAll(engId, req, createdBy));
    }

    @PostMapping("/{taskId}/cancel")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TaskDto cancel(@PathVariable Long engId, @PathVariable Long taskId) {
        return service.cancel(engId, taskId);
    }

    private static Long parseUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
