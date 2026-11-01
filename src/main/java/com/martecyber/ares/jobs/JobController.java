package com.martecyber.ares.jobs;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.JobDto;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService svc;

    private final com.martecyber.ares.users.OrgScopeService orgScope;

    public JobController(JobService svc, com.martecyber.ares.users.OrgScopeService orgScope) {
        this.orgScope = orgScope; this.svc = svc; }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<JobDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) List<String> type,
        @RequestParam(required = false) List<String> status,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "DESC") String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        if (organizationId != null) orgScope.assertCurrentOrgAccess(organizationId);
        if (projectId != null) orgScope.assertCurrentProjectAccess(projectId);
        return PagedResponse.of(svc.list(organizationId, projectId, type, status, sortBy, sortDir, page, size,
            orgScope.currentAccessibleOrgIds()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public JobDto get(@PathVariable Long id) {
        orgScope.assertCurrentCanSeeOrgOwned(svc.organizationIdOf(id));
        return svc.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public JobDto create(@Valid @RequestBody CreateJobRequest req) {
        if (req.organizationId() != null) orgScope.assertCurrentOrgAccess(req.organizationId());
        if (req.projectId() != null) orgScope.assertCurrentProjectAccess(req.projectId());
        return svc.create(req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public JobDto update(@PathVariable Long id, @RequestBody UpdateJobRequest req) {
        orgScope.assertCurrentCanSeeOrgOwned(svc.organizationIdOf(id));
        return svc.update(id, req);
    }

    @GetMapping("/active-count")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public long activeCount() { return svc.countActive(); }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public void cancel(@PathVariable Long id) {
        orgScope.assertCurrentCanSeeOrgOwned(svc.organizationIdOf(id));
        svc.cancel(id);
    }
}
