package com.martecyber.ares.projects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/project-statuses")
public class ProjectStatusController {

    private final ProjectStatusRepository repo;

    public ProjectStatusController(ProjectStatusRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ProjectStatus> list() {
        return repo.findAll();
    }
}
