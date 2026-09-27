package com.martecyber.ares.integrations;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.integrations.dto.*;
import com.martecyber.ares.plugins.PluginRepository;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private final IntegrationService svc;
    private final IntegrationActionRegistry actionRegistry;
    private final PluginRepository pluginRepo;

    public IntegrationController(IntegrationService svc, IntegrationActionRegistry actionRegistry, PluginRepository pluginRepo) {
        this.svc = svc;
        this.actionRegistry = actionRegistry;
        this.pluginRepo = pluginRepo;
    }

    /** Every currently-registered integration type — built-in AND plugin-provided (see
     *  {@code com.martecyber.ares.plugins.PluginLoader}) — so the frontend's "new integration"
     *  picker can stop hardcoding {@code INTEGRATION_TYPES} for anything a plugin brings. {@code
     *  icon} is only ever populated for a plugin-provided type (looked up by matching {@link
     *  IntegrationActionHandler#integrationType()} against {@code Plugin.pluginId} — the two are
     *  the same string by convention, see {@code plugin.json}'s own doc); a built-in type keeps
     *  its own curated icon on the frontend side instead.
     *  <p>{@code dataSourceOnly=true} restricts the result to {@link IntegrationActionHandler#isDataSourceIntegration()}
     *  types — used by the Data Sources "new integration" picker so Workflow-only action shims
     *  (KB sync, Bug Hunting sync, the orphaned {@code shodan-task} connector) don't show up as
     *  something creatable. The Workflow editor's own action catalog keeps calling this endpoint
     *  without the flag, since those shims ARE legitimate choices there. */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<IntegrationTypeDto> types(@RequestParam(defaultValue = "false") boolean dataSourceOnly) {
        return actionRegistry.all().stream()
            .filter(h -> !dataSourceOnly || h.isDataSourceIntegration())
            .map(h -> {
                var plugin = pluginRepo.findByPluginId(h.integrationType());
                return new IntegrationTypeDto(h.integrationType(), h.integrationTypeLabel(), h.describeActions(),
                    plugin.map(p -> p.getIcon()).orElse(null), plugin.map(p -> p.getIconLight()).orElse(null));
            })
            .toList();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<IntegrationDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(svc.list(organizationId, type, status, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public IntegrationDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<IntegrationDto> create(@Valid @RequestBody CreateIntegrationRequest req) {
        IntegrationDto created = svc.create(req);
        return ResponseEntity.created(URI.create("/api/v1/integrations/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public IntegrationDto update(@PathVariable Long id, @RequestBody UpdateIntegrationRequest req) {
        return svc.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public IntegrationDto testConnection(@PathVariable Long id) {
        return svc.testConnection(id);
    }

    // ── MSSP accounts ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/mssp-accounts")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public List<java.util.Map<String, String>> listMsspAccounts(@PathVariable Long id) {
        return svc.listMsspAccounts(id);
    }

    // ── Greenbone tasks ────────────────────────────────────────────────────────

    @GetMapping("/{id}/greenbone-tasks")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public List<java.util.Map<String, String>> listGreenboneTasks(@PathVariable Long id) {
        return svc.listGreenboneTasks(id);
    }

    // ── Grants ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/grants")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<IntegrationGrantDto> listGrants(@PathVariable Long id) {
        return svc.listGrants(id);
    }

    @PostMapping("/{id}/grants")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<IntegrationGrantDto> createGrant(
            @PathVariable Long id,
            @Valid @RequestBody CreateGrantRequest req) {
        IntegrationGrantDto created = svc.createGrant(id, req);
        return ResponseEntity.created(
            URI.create("/api/v1/integrations/" + id + "/grants/" + created.id())
        ).body(created);
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void revokeGrant(@PathVariable Long id, @PathVariable Long grantId) {
        svc.revokeGrant(id, grantId);
    }
}
