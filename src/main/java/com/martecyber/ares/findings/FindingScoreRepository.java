package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingScoreRepository extends JpaRepository<FindingScore, Long> {
    List<FindingScore> findByFindingId(Long findingId);
    boolean existsBySsvcLeafNodeIdIn(List<Long> ssvcLeafNodeIds);
}
