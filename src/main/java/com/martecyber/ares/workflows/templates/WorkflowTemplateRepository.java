package com.martecyber.ares.workflows.templates;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Long> {
    Page<WorkflowTemplate> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<WorkflowTemplate> findByNameIgnoreCase(String name);
}
