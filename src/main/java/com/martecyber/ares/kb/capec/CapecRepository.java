package com.martecyber.ares.kb.capec;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CapecRepository extends JpaRepository<CapecEntry, Long>, JpaSpecificationExecutor<CapecEntry> {

    Optional<CapecEntry> findByCapecId(String capecId);

    List<CapecEntry> findAllByCapecIdIn(Collection<String> capecIds);

    Page<CapecEntry> findByAbstraction(String abstraction, Pageable pageable);

    Page<CapecEntry> findByTypicalSeverity(String severity, Pageable pageable);

    Optional<CapecEntry> findTopByOrderBySyncedAtDesc();
}
