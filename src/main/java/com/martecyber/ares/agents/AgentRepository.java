package com.martecyber.ares.agents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Page<Agent> findAllByOrderByNameAsc(Pageable pageable);

    Optional<Agent> findByEnrollmentCode(String enrollmentCode);

    Optional<Agent> findByTokenHash(String tokenHash);
}
