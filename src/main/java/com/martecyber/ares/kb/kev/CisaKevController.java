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
@RequestMapping("/api/v1/kb/cisa-kev")
public class CisaKevController {

    private final CisaKevService service;

    public CisaKevController(CisaKevService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<CveKevDetail> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "dateAdded") String sortBy,
        @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        String col = "dueDate".equals(sortBy) || "cveId".equals(sortBy) ? sortBy : "dateAdded";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        if (q != null && !q.isBlank()) return service.search(q, pageable);
        return service.findAll(pageable);
    }

    /** Batch lookup — used to flag CVE list rows / references as KEV-listed without an N+1 fetch. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<CisaKevService.StatusDto> status(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveStatus(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CveKevDetail get(@PathVariable String id) {
        return service.findById(id).orElseThrow(() -> new NotFoundException("CISA KEV entry not found: " + id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CisaKevService.SyncStats stats() { return service.stats(); }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }

    /** Wipes the existing KEV catalog and re-fetches it from scratch. */
    @PostMapping("/sync/full")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> syncFull() {
        Long jobId = service.triggerFullSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
