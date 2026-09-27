package com.martecyber.ares.kb.capec;

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
@RequestMapping("/api/v1/kb/capec")
public class CapecController {

    private final CapecService service;

    public CapecController(CapecService service) { this.service = service; }

    private static final Set<String> CAPEC_SORT_FIELDS = Set.of("capecId", "name", "abstraction", "typicalSeverity", "likelihoodOfAttack");

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<CapecEntry> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) List<String> abstraction,
        @RequestParam(required = false) List<String> severity,
        @RequestParam(required = false) String cwe,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> likelihoodOfAttack,
        @RequestParam(required = false) String aql,
        @RequestParam(defaultValue = "capecId") String sortBy,
        @RequestParam(defaultValue = "ASC") String sortDir
    ) {
        String col = CAPEC_SORT_FIELDS.contains(sortBy) ? sortBy : "capecId";
        Sort.Direction dir = "DESC".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        // When aql is present, discrete filter params are ignored rather than merged — same
        // coexistence rule as /api/v1/kb/cve?aql=....
        if (aql != null && !aql.isBlank()) return service.findByAql(aql, pageable);
        return service.filter(q, abstraction, severity, likelihoodOfAttack, cwe, pageable);
    }

    @GetMapping("/names")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<CapecService.NameDto> names(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveNames(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CapecEntry get(@PathVariable String id) {
        return service.findById(id).orElseThrow(() -> new NotFoundException("CAPEC not found: " + id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CapecService.SyncStats stats() { return service.stats(); }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
