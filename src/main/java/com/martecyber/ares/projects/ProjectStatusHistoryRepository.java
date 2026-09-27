package com.martecyber.ares.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectStatusHistoryRepository extends JpaRepository<ProjectStatusHistory, ProjectStatusHistoryId> {
    List<ProjectStatusHistory> findByIdProjectIdOrderByIdChangedAtAsc(Long projectId);
}
