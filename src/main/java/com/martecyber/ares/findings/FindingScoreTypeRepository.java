package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FindingScoreTypeRepository extends JpaRepository<FindingScoreType, Long> {
    Optional<FindingScoreType> findByTitle(String title);
}
