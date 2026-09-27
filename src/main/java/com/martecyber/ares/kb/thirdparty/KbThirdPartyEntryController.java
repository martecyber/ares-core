package com.martecyber.ares.kb.thirdparty;

import com.martecyber.ares.common.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/kb/third-party-entries")
public class KbThirdPartyEntryController {

    private final KbThirdPartyEntryService service;

    public KbThirdPartyEntryController(KbThirdPartyEntryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<KbThirdPartyEntry> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "50")  int size,
            @RequestParam(defaultValue = "value")  String sortBy,
            @RequestParam(defaultValue = "ASC")    String sortDir) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String col = Set.of("value", "kind", "category", "enabled", "createdAt").contains(sortBy) ? sortBy : "value";
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        return PagedResponse.of(service.list(category, kind, enabled, q, pageable));
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbThirdPartyEntry create(@RequestBody KbThirdPartyEntry body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbThirdPartyEntry update(@PathVariable Long id, @RequestBody KbThirdPartyEntry body) {
        return service.update(id, body);
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> toggle(@PathVariable Long id) {
        service.toggle(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Manual "force reclassify" — creates and immediately runs a platform-wide reclassify Job. */
    @PostMapping("/reclassify")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public Long reclassify() {
        return service.reclassifyAsJob();
    }
}
