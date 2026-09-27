package com.martecyber.ares.kb.owasp;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/owasp")
public class OwaspController {

    private final OwaspService service;

    public OwaspController(OwaspService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<OwaspEntry> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String aql) {
        // When aql is present, discrete filter params are ignored rather than merged — same
        // coexistence rule as /api/v1/kb/cve?aql=....
        if (aql != null && !aql.isBlank()) return service.findByAql(aql);
        if (q != null && !q.isBlank()) return service.search(q, year);
        return service.findAll(year);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public OwaspEntry get(@PathVariable String id, @RequestParam(required = false) Integer year) {
        return service.findById(id, year).orElseThrow(() -> new NotFoundException("OWASP entry not found: " + id));
    }

    @GetMapping("/years")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<Integer> years() { return service.availableYears(); }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public OwaspService.SyncStats stats() { return service.stats(); }

    @PostMapping("/seed")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> seed() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
