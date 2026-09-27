package com.martecyber.ares.kb.attack;

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
@RequestMapping("/api/v1/kb/attack")
public class AttackController {

    private final AttackService service;

    public AttackController(AttackService service) { this.service = service; }

    @GetMapping("/tactics")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackTactic> tactics(@RequestParam(defaultValue = "enterprise-attack") String matrix) {
        return service.tacticsByMatrix(matrix);
    }

    @GetMapping("/techniques")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<AttackTechnique> techniques(
        @RequestParam(defaultValue = "enterprise-attack") String matrix,
        @RequestParam(required = false) String tactic,
        @RequestParam(required = false, defaultValue = "false") boolean subtechniques,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by("attackId"));
        // When aql is present, discrete filter params are ignored rather than merged — same
        // coexistence rule as /api/v1/kb/cve?aql=.... Note aql only ever queries AttackTechnique
        // (matrix/tactic filtering is itself expressible in AQL via the matrix/subtechnique fields).
        if (aql != null && !aql.isBlank()) return service.findByAql(aql, pageable);
        if (q != null && !q.isBlank()) return service.searchTechniques(matrix, q, pageable);
        return service.techniques(matrix, tactic, subtechniques, pageable);
    }

    @GetMapping("/techniques/names")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackService.NameDto> techniqueNames(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return service.resolveTechniqueNames(ids);
    }

    // NOTE: /techniques/all and /techniques/names MUST be declared before /techniques/{id}
    // so Spring MVC's literal-path-wins rule is explicit and not order-dependent.
    @GetMapping("/techniques/all")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackTechnique> allTechniques(
        @RequestParam(defaultValue = "enterprise-attack") String matrix
    ) {
        return service.allTechniquesForMatrix(matrix);
    }

    @GetMapping("/techniques/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public AttackTechnique getTechnique(
        @PathVariable String id,
        @RequestParam(defaultValue = "enterprise-attack") String matrix
    ) {
        return service.findTechnique(id, matrix)
            .orElseThrow(() -> new NotFoundException("ATT&CK technique not found: " + id));
    }

    @GetMapping("/techniques/{id}/mitigations")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackMitigation> techniqueMitigations(
        @PathVariable String id,
        @RequestParam(defaultValue = "enterprise-attack") String matrix
    ) {
        AttackTechnique t = service.findTechnique(id, matrix)
            .orElseThrow(() -> new NotFoundException("ATT&CK technique not found: " + id));
        return service.mitigationsForTechnique(t.getId());
    }

    @GetMapping("/techniques/{id}/subtechniques")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackService.NameDto> subtechniques(
        @PathVariable String id,
        @RequestParam(defaultValue = "enterprise-attack") String matrix
    ) {
        return service.subtechniquesOf(id, matrix).stream()
            .map(t -> new AttackService.NameDto(t.getAttackId(), t.getName()))
            .toList();
    }

    @GetMapping("/mitigations")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Page<AttackMitigation> mitigations(
        @RequestParam(defaultValue = "enterprise-attack") String matrix,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by("attackId"));
        if (q != null && !q.isBlank()) return service.searchMitigations(matrix, q, pageable);
        return service.mitigations(matrix, pageable);
    }

    @GetMapping("/mitigations/all")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AttackMitigation> allMitigations(
        @RequestParam(defaultValue = "enterprise-attack") String matrix
    ) {
        return service.allMitigationsForMatrix(matrix);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public AttackService.SyncStats stats() { return service.stats(); }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Map<String, Object>> sync() {
        Long jobId = service.triggerSync();
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }
}
