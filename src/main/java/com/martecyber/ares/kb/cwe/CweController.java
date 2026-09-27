package com.martecyber.ares.kb.cwe;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/kb/cwe")
public class CweController {

    private final CweService service;

    public CweController(CweService service) { this.service = service; }

    private static final Set<String> CWE_SORT_FIELDS = Set.of("code", "name", "type", "abstraction", "likelihoodOfExploit");

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<CweEntry> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) List<String> type,
        @RequestParam(required = false) List<String> abstraction,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> likelihoodOfExploit,
        @RequestParam(required = false) String aql,
        @RequestParam(defaultValue = "code") String sortBy,
        @RequestParam(defaultValue = "ASC") String sortDir
    ) {
        String col = CWE_SORT_FIELDS.contains(sortBy) ? sortBy : "code";
        Sort.Direction dir = "DESC".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        // When aql is present, discrete filter params are ignored rather than merged — same
        // coexistence rule as /api/v1/kb/cve?aql=....
        if (aql != null && !aql.isBlank()) return service.findByAql(aql, pageable);
        return service.filter(q, type, abstraction, likelihoodOfExploit, pageable);
    }

    @GetMapping("/names")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<CweService.NameDto> names(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveNames(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CweEntry get(@PathVariable String id) {
        return service.findById(id).orElseThrow(() -> new NotFoundException("CWE not found: " + id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CweService.SyncStats stats() { return service.stats(); }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
