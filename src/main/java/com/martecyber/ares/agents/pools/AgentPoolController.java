package com.martecyber.ares.agents.pools;

import com.martecyber.ares.agents.pools.dto.AgentPoolDtos.*;
import com.martecyber.ares.agents.pools.dto.PoolTimelineDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent-pools")
public class AgentPoolController {

    private final AgentPoolService service;

    private final com.martecyber.ares.users.OrgScopeService orgScope;

    public AgentPoolController(AgentPoolService service, com.martecyber.ares.users.OrgScopeService orgScope) {
        this.orgScope = orgScope;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<PoolDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PoolDto get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<PoolDto> create(@RequestBody CreatePool req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PoolDto update(@PathVariable Long id, @RequestBody UpdatePool req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Members ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<Long> listMembers(@PathVariable Long id) { return service.listMembers(id); }

    @PutMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> setMembers(@PathVariable Long id, @RequestBody SetMembers req) {
        service.setMembers(id, req.agentIds());
        return ResponseEntity.noContent().build();
    }

    // ── Grants ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/grants")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<GrantDto> listGrants(@PathVariable Long id) {
        // Pools are shared infrastructure, but each grant targets one client — only show the caller's.
        var visible = orgScope.currentAccessibleOrgIds();
        return service.listGrants(id).stream()
            .filter(g -> orgScope.canSeeOrgOwned(visible, g.organizationId()))
            .toList();
    }

    @PostMapping("/{id}/grants")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<GrantDto> createGrant(@PathVariable Long id, @RequestBody CreateGrant req) {
        orgScope.assertCurrentOrgAccess(req.organizationId());
        if (req.projectId() != null) orgScope.assertCurrentProjectAccess(req.projectId());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGrant(id, req));
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> revokeGrant(@PathVariable Long id, @PathVariable Long grantId) {
        orgScope.assertCurrentCanSeeOrgOwned(service.organizationIdOfGrant(grantId));
        service.revokeGrant(grantId);
        return ResponseEntity.noContent().build();
    }

    // ── Timeline ───────────────────────────────────────────────────────────

    /**
     * Gantt-style snapshot of the last {@code hours} of agent activity in this
     * pool, plus the current claimable-pending queue size. The frontend uses
     * this to draw rows-per-agent-slot and a queue indicator.
     */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PoolTimelineDto timeline(@PathVariable Long id,
                                     @RequestParam(defaultValue = "24") int hours) {
        return service.getTimeline(id, Math.max(1, Math.min(hours, 168)));
    }
}
