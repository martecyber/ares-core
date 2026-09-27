package com.martecyber.ares.kb.cve;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PagedResponse;
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
@RequestMapping("/api/v1/kb/cve")
public class CveController {

    private final CveService service;

    public CveController(CveService service) { this.service = service; }

    private static final Set<String> CVE_SORT_FIELDS = Set.of("publishedAt", "lastModifiedAt", "severity", "cvssScore", "cveId", "anyKevListed", "exploitCount");

    /** Maps the Java entity property names above to their actual ares.cve column names — needed
     *  only for {@link CveRepository#search}, which is a native SQL query: Spring Data appends a
     *  Pageable's Sort to a native query using the property name verbatim (unlike JPQL/derived
     *  queries, where Hibernate translates it via @Column), so passing e.g. "publishedAt" straight
     *  through produced "order by publishedAt desc" — Postgres folds that unquoted identifier to
     *  "publishedat", which doesn't exist (real column: published_at). */
    private static final Map<String, String> CVE_SORT_COLUMNS = Map.of(
        "publishedAt", "published_at",
        "lastModifiedAt", "last_modified_at",
        "severity", "severity",
        "cvssScore", "cvss_score",
        "cveId", "cve_id",
        "anyKevListed", "any_kev_listed",
        "exploitCount", "exploit_count"
    );

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<CveEntry> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) List<String> severity,
        /** Any of "cisa", "vulncheck", "none" — OR'd together when multiple are given. */
        @RequestParam(required = false) List<String> kev,
        /** Any of "yes", "no" — OR'd together when multiple are given (selecting both is a no-op filter). */
        @RequestParam(required = false) List<String> poc,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql,
        @RequestParam(defaultValue = "publishedAt") String sortBy,
        @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        String col = CVE_SORT_FIELDS.contains(sortBy) ? sortBy : "publishedAt";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(dir, col));
        // When aql is present, discrete filter params are ignored rather than merged (same
        // coexistence rule as /api/v1/detections?aql=...) — also the moment this fixes the
        // pre-existing gap where severity/kev/poc/q could only be applied one at a time.
        if (aql != null && !aql.isBlank()) return PagedResponse.of(service.findByAql(aql, pageable));
        if (q != null && !q.isBlank()) {
            var nativePageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(dir, CVE_SORT_COLUMNS.getOrDefault(col, "published_at")));
            return PagedResponse.of(service.search(q, nativePageable));
        }
        if (severity != null && !severity.isEmpty()) return PagedResponse.of(service.findBySeverityIn(severity, pageable));
        if (kev != null && !kev.isEmpty()) return PagedResponse.of(service.findByKevSources(kev, pageable));
        if (poc != null && !poc.isEmpty()) return PagedResponse.of(service.findByPocStatus(poc, pageable));
        return PagedResponse.of(service.findAll(pageable));
    }

    @GetMapping("/severities")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<CveService.SeverityDto> severities(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveSeverities(ids);
    }

    @GetMapping("/descriptions")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<CveService.DescriptionDto> descriptions(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveDescriptions(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CveEntry get(@PathVariable String id) {
        return service.findById(id).orElseThrow(() -> new NotFoundException("CVE not found: " + id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public CveService.SyncStats stats() { return service.stats(); }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }

    @PostMapping("/sync/full")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> syncFull() {
        Long jobId = service.triggerFullSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
