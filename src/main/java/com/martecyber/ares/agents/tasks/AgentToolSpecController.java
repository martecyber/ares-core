package com.martecyber.ares.agents.tasks;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User-session-facing counterpart to {@code AgentRuntimeController#toolSpecs} (agent-token
 * gated, under {@code /api/v1/agent/tool-specs}) — same {@link AgentToolSpecRegistry} data,
 * separate path/auth guard since a logged-in operator building a workflow's "Agent Action" node
 * (the tool picker + generic {@code ToolConfigForm.vue}) is a normal browser session, never an
 * agent token.
 */
@RestController
@RequestMapping("/api/v1/agent-tool-specs")
public class AgentToolSpecController {

    private final AgentToolSpecRegistry registry;

    public AgentToolSpecController(AgentToolSpecRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AgentToolSpec> list() {
        return registry.list();
    }
}
