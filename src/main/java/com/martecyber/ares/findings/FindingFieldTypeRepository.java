package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingFieldTypeRepository extends JpaRepository<FindingFieldType, Long> {
    List<FindingFieldType> findAllByOrderBySortOrderAscTitleAsc();
    List<FindingFieldType> findByRequiredTrueOrderBySortOrderAsc();
    boolean existsByName(String name);
}
