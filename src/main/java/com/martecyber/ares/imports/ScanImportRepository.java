package com.martecyber.ares.imports;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScanImportRepository extends JpaRepository<ScanImport, Long> {
    /** Newest first, paginated — a long-lived MONITOR project's rescans can otherwise accumulate
     *  an unbounded number of rows (ImportService#listForProject). */
    Page<ScanImport> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);
}
