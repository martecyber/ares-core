package com.martecyber.ares.integrations.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.martecyber.ares.integrations.notifications.dto.MessagingDtos.*;

import java.util.List;

/** Admin CRUD for messaging integrations + a "Test" endpoint to verify config. */
@RestController
@RequestMapping("/api/v1/messaging-integrations")
public class MessagingIntegrationController {

    private final MessagingService service;

    public MessagingIntegrationController(MessagingService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<IntegrationDto> list() { return service.list(); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public IntegrationDto get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<IntegrationDto> create(@RequestBody CreateIntegrationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public IntegrationDto update(@PathVariable Long id, @RequestBody UpdateIntegrationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> test(@PathVariable Long id, @RequestBody(required = false) TestRequest req) {
        service.sendTest(id, req);
        return ResponseEntity.noContent().build();
    }

    // ── Grants ────────────────────────────────────────────────────

    @GetMapping("/{id}/grants")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<GrantDto> listGrants(@PathVariable Long id) {
        return service.listGrants(id);
    }

    @PostMapping("/{id}/grants")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<GrantDto> createGrant(@PathVariable Long id,
                                                  @RequestBody CreateGrantRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGrant(id, req));
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> revokeGrant(@PathVariable Long id, @PathVariable Long grantId) {
        service.revokeGrant(grantId);
        return ResponseEntity.noContent().build();
    }
}
