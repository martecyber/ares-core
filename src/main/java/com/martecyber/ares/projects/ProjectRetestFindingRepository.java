package com.martecyber.ares.projects;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRetestFindingRepository extends JpaRepository<ProjectRetestFinding, Long> {

    List<ProjectRetestFinding> findByRetestProjectIdOrderByLinkedAtDesc(Long retestProjectId);

    Optional<ProjectRetestFinding> findByRetestProjectIdAndFindingId(Long retestProjectId, Long findingId);

    boolean existsByRetestProjectIdAndFindingId(Long retestProjectId, Long findingId);

    void deleteByRetestProjectIdAndFindingId(Long retestProjectId, Long findingId);
}
