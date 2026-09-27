package com.martecyber.ares.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectScopeEntryRepository extends JpaRepository<ProjectScopeEntry, Long> {
    List<ProjectScopeEntry> findByProjectIdOrderByCreatedAtAsc(Long projectId);
    void deleteByProjectIdAndId(Long projectId, Long id);
    void deleteByProjectIdAndSourceNot(Long projectId, String source);
    List<ProjectScopeEntry> findByProjectIdAndSource(Long projectId, String source);
    java.util.Optional<ProjectScopeEntry> findByProjectIdAndSourceAndExternalId(
            Long projectId, String source, String externalId);
}
