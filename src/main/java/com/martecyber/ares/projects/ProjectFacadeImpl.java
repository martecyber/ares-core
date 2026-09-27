package com.martecyber.ares.projects;

import com.martecyber.ares.common.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Thin adapter exposing the narrow slice of {@link ProjectRepository} plugins need as the
 *  {@code ares-sdk}-owned {@link ProjectFacade}. */
@Component
class ProjectFacadeImpl implements ProjectFacade {

    private final ProjectRepository projectRepository;

    ProjectFacadeImpl(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public Long getOrganizationId(Long projectId) {
        return projectRepository.findOrganizationIdById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));
    }

    @Override
    public boolean exists(Long projectId) {
        return projectRepository.existsById(projectId);
    }

    @Override
    public Optional<String> getProjectTypeCode(Long projectId) {
        return projectRepository.findTypeCodeById(projectId);
    }
}
