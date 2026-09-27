package com.martecyber.ares.kb.kev;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/vulncheck-kev")
public class VulnCheckKevController {

    private final VulnCheckKevService service;

    public VulnCheckKevController(VulnCheckKevService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<CveKevDetail> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "syncedAt") String sortBy,
        @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        String col = "dueDate".equals(sortBy) ? sortBy : "syncedAt";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        if (q != null && !q.isBlank()) return service.search(q, pageable);
        return service.findAll(pageable);
    }

    /** Batch lookup — used to flag CVE list rows / references as VulnCheck-KEV-listed. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<VulnCheckKevService.StatusDto> status(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveStatus(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CveKevDetail get(@PathVariable String id) {
        return service.findByCve(id).orElseThrow(() -> new NotFoundException("VulnCheck KEV entry not found: " + id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public VulnCheckKevService.SyncStats stats() { return service.stats(); }

    public record ApiKeyRequest(String apiKey) {}

    /** Write-only — the stored key is never returned by any endpoint. */
    @PutMapping("/api-key")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> setApiKey(@RequestBody ApiKeyRequest req) {
        if (req.apiKey() == null || req.apiKey().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey must not be blank"));
        service.setApiKey(req.apiKey().trim());
        return ResponseEntity.ok(Map.of("configured", true));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }

    /** Wipes the existing VulnCheck KEV catalog and re-fetches it from scratch. */
    @PostMapping("/sync/full")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> syncFull() {
        Long jobId = service.triggerFullSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
