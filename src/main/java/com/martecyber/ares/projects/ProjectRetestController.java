package com.martecyber.ares.projects;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.projects.dto.LinkRetestFindingsRequest;
import com.martecyber.ares.projects.dto.ProjectRetestFindingDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/retest")
public class ProjectRetestController {

    private final ProjectRetestService service;

    public ProjectRetestController(ProjectRetestService service) {
        this.service = service;
    }

    @GetMapping("/findings")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ProjectRetestFindingDto> list(@PathVariable Long projectId) {
        return service.list(projectId);
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<FindingDto> searchCandidates(@PathVariable Long projectId,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return PagedResponse.of(service.searchCandidates(projectId, severity, page, size));
    }

    @PostMapping("/findings")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> link(@PathVariable Long projectId, @RequestBody LinkRetestFindingsRequest req) {
        service.link(projectId, req.findingIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/findings/{findingId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> unlink(@PathVariable Long projectId, @PathVariable Long findingId) {
        service.unlink(projectId, findingId);
        return ResponseEntity.noContent().build();
    }
}
