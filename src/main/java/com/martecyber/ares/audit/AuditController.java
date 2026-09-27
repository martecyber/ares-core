package com.martecyber.ares.audit;

import com.martecyber.ares.audit.dto.AuditLogDto;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class AuditController {

    private final AuditLogRepository repo;

    public AuditController(AuditLogRepository repo) { this.repo = repo; }

    @GetMapping
    public PagedResponse<AuditLogDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long actorId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String resourceType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size
    ) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return PagedResponse.of(
            repo.filter(organizationId, actorId, action, resourceType, p)
                .map(AuditLogDto::from));
    }

    @GetMapping("/{id}")
    public AuditLogDto get(@PathVariable Long id) {
        return repo.findById(id)
            .map(AuditLogDto::from)
            .orElseThrow(() -> NotFoundException.of("audit_log", id));
    }
}
