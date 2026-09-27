package com.martecyber.ares.agents.tasks.templates;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentTaskTemplateRepository extends JpaRepository<AgentTaskTemplate, Long> {
    Page<AgentTaskTemplate> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<AgentTaskTemplate> findByNameIgnoreCase(String name);
}
