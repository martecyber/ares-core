package com.martecyber.ares.kb.cwe;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CweRepository extends JpaRepository<CweEntry, Long>, JpaSpecificationExecutor<CweEntry> {

    Optional<CweEntry> findByCweId(String cweId);

    List<CweEntry> findAllByCweIdIn(Collection<String> cweIds);

    Page<CweEntry> findByType(String type, Pageable pageable);

    Page<CweEntry> findByAbstraction(String abstraction, Pageable pageable);

    Optional<CweEntry> findTopByOrderBySyncedAtDesc();

    long countByType(String type);
}
