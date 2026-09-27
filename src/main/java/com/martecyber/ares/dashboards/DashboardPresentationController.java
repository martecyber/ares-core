package com.martecyber.ares.dashboards;

import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** SOC-screen dashboard presentations — entirely staff-only (no client-facing use case at all),
 *  so unlike {@link DashboardController} the role gate lives fully at the class level here. */
@RestController
@RequestMapping("/api/v1/dashboard-presentations")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
public class DashboardPresentationController {

    private final DashboardPresentationService service;

    public DashboardPresentationController(DashboardPresentationService service) {
        this.service = service;
    }

    @GetMapping
    public List<PresentationSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PresentationDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public PresentationSummary create(@RequestBody CreatePresentationRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public PresentationSummary update(@PathVariable Long id, @RequestBody UpdatePresentationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/items")
    public ResponseEntity<Void> saveItems(@PathVariable Long id, @RequestBody SavePresentationItemsRequest req) {
        service.saveItems(id, req);
        return ResponseEntity.noContent().build();
    }
}
