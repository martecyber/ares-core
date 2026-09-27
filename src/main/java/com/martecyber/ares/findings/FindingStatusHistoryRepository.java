package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingStatusHistoryRepository extends JpaRepository<FindingStatusHistory, FindingStatusHistory.FindingStatusHistoryId> {
    List<FindingStatusHistory> findByIdFindingIdOrderByIdChangedAtAsc(Long findingId);
}
