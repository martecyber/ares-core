package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FindingStatusRepository extends JpaRepository<FindingStatus, Long> {
    Optional<FindingStatus> findByName(String name);
}
