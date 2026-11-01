package com.martecyber.ares.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectIterationRepository extends JpaRepository<ProjectIteration, Long> {

    Optional<ProjectIteration> findByProjectIdAndLabel(Long projectId, String label);

    List<ProjectIteration> findByProjectIdOrderByPlannedStartAsc(Long projectId);
}
