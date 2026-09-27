package com.martecyber.ares.agents;

import com.martecyber.ares.agents.dto.AgentRequests.*;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.TaskAssignment;
import com.martecyber.ares.common.NotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Agent-facing endpoints under /api/v1/agent. Most require X-Agent-Token (enforced by
 * AgentTokenAuthFilter → ROLE_AGENT). /enroll is the one-time exception: it's permitted
 * without a token because it MINTS one in exchange for a valid enrollment_code.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentRuntimeController {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeController.class);
    /** Refuse to signal tasksAvailable when the agent reports less than this free on disk. */
    private static final long DISK_MIN_FREE_BYTES = 512L * 1024 * 1024; // 512 MB

    private final AgentService service;
    private final AgentTaskService tasks;
    private final AgentToolSpecRegistry toolSpecRegistry;

    @Value("${ares.versions.agent:1.0.0-beta21}")
    private String latestAgentVersion;

    public AgentRuntimeController(AgentService service, AgentTaskService tasks, AgentToolSpecRegistry toolSpecRegistry) {
        this.service = service;
        this.tasks = tasks;
        this.toolSpecRegistry = toolSpecRegistry;
    }

    /** One-time enrollment. Returns the long-lived token (only shown once). */
    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.OK)
    public EnrollResponse enroll(@RequestBody EnrollRequest req) {
        return service.enroll(req);
    }

    /** Periodic heartbeat. Requires X-Agent-Token. */
    @PostMapping("/heartbeat")
    @PreAuthorize("hasRole('AGENT')")
    public HeartbeatResponse heartbeat(@RequestBody HeartbeatRequest req) {
        Long agentId = requireAgent();
        // Reconcile in-flight tasks: reset any that the agent is no longer running.
        tasks.reconcileAgentTasks(agentId, req != null ? req.activeTaskIds() : null);

        // Suppress dispatch if the agent reports critically low disk space.
        boolean diskOk = true;
        if (req != null && req.diskFreeBytes() != null) {
            diskOk = req.diskFreeBytes() >= DISK_MIN_FREE_BYTES;
            if (!diskOk) {
                log.warn("Agent {} has critically low disk space: {} MB free — suppressing task dispatch",
                         agentId, req.diskFreeBytes() / 1024 / 1024);
            }
        }

        boolean hasWork = diskOk && tasks.hasPendingForAgent(agentId);
        HeartbeatResponse base = service.heartbeat(agentId, req, latestAgentVersion);
        return new HeartbeatResponse(base.latestAgentVersion(), base.heartbeatIntervalSeconds(), hasWork, base.toolSpecsVersion());
    }

    /**
     * Returns the agent's IP as the server sees it. Used by the agent when targets are
     * public — the server-observed connect IP is the post-NAT public address.
     * Prefers X-Forwarded-For (first hop) when behind a reverse proxy.
     */
    @GetMapping("/my-ip")
    @PreAuthorize("hasRole('AGENT')")
    public java.util.Map<String, String> myIp(jakarta.servlet.http.HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        String ip;
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            ip = (comma < 0 ? xff : xff.substring(0, comma)).trim();
        } else {
            ip = req.getRemoteAddr();
        }
        return java.util.Map.of("ip", ip);
    }

    // ── Tool specs ───────────────────────────────────────────────────────

    /**
     * Full current catalog of agent-executable tools — every installed, enabled plugin's {@code
     * AgentToolSpec}. The agent caches this against {@link HeartbeatResponse#toolSpecsVersion}
     * and only re-fetches when that counter changes, rather than on every 30s heartbeat.
     */
    @GetMapping("/tool-specs")
    @PreAuthorize("hasRole('AGENT')")
    public List<AgentToolSpec> toolSpecs() {
        return toolSpecRegistry.list();
    }

    /**
     * Raw bytes of a tool's optional {@code customBuilder} module (see {@link
     * AgentToolSpec.CustomBuilder}'s own doc) — the agent syncs and checksum-verifies this once,
     * caches it locally, and only re-fetches when the spec's declared sha256 no longer matches
     * what it has cached. 404 for a tool with no {@code customBuilder} declared at all.
     */
    @GetMapping(value = "/tool-specs/{toolId}/builder", produces = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<byte[]> toolBuilderModule(@PathVariable String toolId) {
        byte[] bytes = toolSpecRegistry.findBuilderModule(toolId)
            .orElseThrow(() -> NotFoundException.of("agent tool builder module", toolId));
        return ResponseEntity.ok(bytes);
    }

    // ── Task lifecycle ────────────────────────────────────────────────────

    /**
     * Returns tasks still in dispatched/running state assigned to this agent.
     * Called once at startup so the agent can re-execute any work that was in-flight
     * when it last exited (crash, update restart, etc.).
     */
    @GetMapping("/tasks/assigned")
    @PreAuthorize("hasRole('AGENT')")
    public java.util.List<TaskAssignment> assigned() {
        return tasks.assignedToAgent(requireAgent());
    }

    /** Agent claims one task from any pool it belongs to. 204 if nothing pending. */
    @PostMapping("/tasks/claim")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<TaskAssignment> claim() {
        Optional<TaskAssignment> next = tasks.claimNext(requireAgent());
        return next.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Agent claims up to {@code count} tasks in a single round-trip. Returns an empty list
     * when nothing is pending (200 + []) rather than 204, so the agent can reliably detect
     * the empty case without content-type sniffing.
     */
    @PostMapping("/tasks/claim-batch")
    @PreAuthorize("hasRole('AGENT')")
    public List<TaskAssignment> claimBatch(@RequestParam(defaultValue = "1") int count) {
        return tasks.claimNextBatch(requireAgent(), Math.min(Math.max(count, 1), 16));
    }

    /** Agent reports execution has actually begun. */
    @PostMapping("/tasks/{id}/started")
    @PreAuthorize("hasRole('AGENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void started(@PathVariable Long id) {
        tasks.markStarted(requireAgent(), id);
    }

    /**
     * Agent uploads tool output. Multipart carries the raw file + execution metadata.
     * The agent may include `sourceIp` here — it's the runtime-determined vantage point
     * (private interface IP or public NAT'd IP, depending on the targets). When present
     * it overrides whatever was stored on the task at creation time.
     */
    @PostMapping(value = "/tasks/{id}/complete",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('AGENT')")
    public void complete(@PathVariable Long id,
                         @RequestPart("file") MultipartFile file,
                         @RequestParam(required = false) Integer exitCode,
                         @RequestParam(required = false) String stderr,
                         @RequestParam(required = false) String sourceIp) throws Exception {
        tasks.complete(requireAgent(), id, file.getBytes(), exitCode, stderr, sourceIp);
    }

    /**
     * Agent polls this while a task subprocess is running to detect a server-side
     * cancellation (triggered by a project user or MSSP admin via the UI).
     * Returns {@code {"cancelled": true}} so the agent can kill the process immediately.
     */
    @GetMapping("/tasks/{id}/cancelled")
    @PreAuthorize("hasRole('AGENT')")
    public java.util.Map<String, Boolean> isCancelled(@PathVariable Long id) {
        return java.util.Map.of("cancelled", tasks.isCancelled(requireAgent(), id));
    }

    /** Agent gives up before producing a result (tool crashed, args couldn't build, …). */
    @PostMapping("/tasks/{id}/fail")
    @PreAuthorize("hasRole('AGENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fail(@PathVariable Long id, @RequestBody(required = false) FailBody body) {
        tasks.fail(requireAgent(), id, body != null ? body.error() : null);
    }

    public record FailBody(String error) {}

    // ── helpers ───────────────────────────────────────────────────────────

    private static Long requireAgent() {
        Long agentId = AgentTokenAuthFilter.currentAgentId();
        if (agentId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return agentId;
    }
}
