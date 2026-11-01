package com.martecyber.ares.projects.rules;

import com.martecyber.ares.projects.rules.dto.CreateProjectRuleRequest;
import com.martecyber.ares.projects.rules.dto.ProjectRuleDto;
import com.martecyber.ares.projects.rules.dto.UpdateProjectRuleRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/rules")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
public class ProjectRuleController {

    private final ProjectRuleService service;

    private final com.martecyber.ares.users.OrgScopeService orgScope;

    public ProjectRuleController(ProjectRuleService service, com.martecyber.ares.users.OrgScopeService orgScope) {
        this.orgScope = orgScope;
        this.service = service;
    }

    @GetMapping
    public List<ProjectRuleDto> list(@PathVariable Long projectId) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectRuleDto create(@PathVariable Long projectId,
                                  @RequestBody CreateProjectRuleRequest req) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.create(projectId, req);
    }

    @PatchMapping("/{id}")
    public ProjectRuleDto update(@PathVariable Long projectId,
                                  @PathVariable Long id,
                                  @RequestBody UpdateProjectRuleRequest req) {
        orgScope.assertCurrentProjectAccess(projectId);
        return service.update(projectId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @PathVariable Long id) {
        orgScope.assertCurrentProjectAccess(projectId);
        service.delete(projectId, id);
    }
}
