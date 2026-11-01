package com.martecyber.ares.projects.testing;

import com.martecyber.ares.projects.testing.dto.ProjectTestingGuideDto;
import com.martecyber.ares.projects.testing.dto.ProjectTestingGuideItemDto;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Per-project testing checklists (assigned KB guides + item status/notes). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testing-guides")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
public class ProjectTestingGuideController {

    private final ProjectTestingGuideService service;

    private final com.martecyber.ares.users.OrgScopeService orgScope;

    public ProjectTestingGuideController(ProjectTestingGuideService service, com.martecyber.ares.users.OrgScopeService orgScope) {
        this.orgScope = orgScope;
        this.service = service;
    }

    @GetMapping
    public List<ProjectTestingGuideDto> list(@PathVariable Long projectId) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectTestingGuideDto assign(@PathVariable Long projectId,
                                         @RequestBody ProjectTestingGuideService.AssignRequest req) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.assign(projectId, req.guideId());
    }

    @PostMapping("/{ptgId}/resync")
    public ProjectTestingGuideDto resync(@PathVariable Long projectId, @PathVariable Long ptgId) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.resync(projectId, ptgId);
    }

    @DeleteMapping("/{ptgId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long projectId, @PathVariable Long ptgId) {
        orgScope.assertCurrentProjectAccess(projectId);
        service.remove(projectId, ptgId);
    }

    @PatchMapping("/{ptgId}/items/{itemId}")
    public ProjectTestingGuideItemDto updateItem(@PathVariable Long projectId,
                                                 @PathVariable Long ptgId,
                                                 @PathVariable Long itemId,
                                                 @RequestBody ProjectTestingGuideService.ItemPatch patch) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.updateItem(projectId, ptgId, itemId, patch);
    }
}
