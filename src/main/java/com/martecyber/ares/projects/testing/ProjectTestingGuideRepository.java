package com.martecyber.ares.projects.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectTestingGuideRepository extends JpaRepository<ProjectTestingGuide, Long> {

    List<ProjectTestingGuide> findByProjectIdOrderByAssignedAtAsc(Long projectId);
}
