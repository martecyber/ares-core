package com.martecyber.ares.projects.rules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRuleRepository extends JpaRepository<ProjectRule, Long> {

    List<ProjectRule> findByProjectId(Long projectId);

    List<ProjectRule> findByProjectIdAndEnabled(Long projectId, boolean enabled);

    Optional<ProjectRule> findByProjectIdAndRuleTypeAndSyncedFrom(Long projectId, String ruleType, String syncedFrom);
}
