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

    public ProjectRuleController(ProjectRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectRuleDto> list(@PathVariable Long projectId) {
        return service.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectRuleDto create(@PathVariable Long projectId,
                                  @RequestBody CreateProjectRuleRequest req) {
        return service.create(projectId, req);
    }

    @PatchMapping("/{id}")
    public ProjectRuleDto update(@PathVariable Long projectId,
                                  @PathVariable Long id,
                                  @RequestBody UpdateProjectRuleRequest req) {
        return service.update(projectId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @PathVariable Long id) {
        service.delete(projectId, id);
    }
}
