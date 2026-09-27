package com.martecyber.ares.projects.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Thin adapter exposing {@link ProjectRuleService} to plugins as the {@code ares-sdk}-owned
 *  {@link ProjectRulesFacade}. */
@Component
class ProjectRulesFacadeImpl implements ProjectRulesFacade {

    private final ProjectRuleService projectRuleService;

    ProjectRulesFacadeImpl(ProjectRuleService projectRuleService) {
        this.projectRuleService = projectRuleService;
    }

    @Override
    public void syncPlatformTestingRequirements(Long projectId, String platform, Map<String, String> requirements) {
        projectRuleService.syncPlatformTestingRequirements(projectId, platform, requirements);
    }
}
