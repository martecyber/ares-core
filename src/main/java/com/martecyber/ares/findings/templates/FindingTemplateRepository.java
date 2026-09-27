package com.martecyber.ares.findings.templates;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FindingTemplateRepository extends JpaRepository<FindingTemplate, Long>, JpaSpecificationExecutor<FindingTemplate> {
    Page<FindingTemplate> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<FindingTemplate> findByTitleIgnoreCase(String title);

    @Query("""
        SELECT t FROM FindingTemplate t
        WHERE :qLike IS NULL OR LOWER(t.title) LIKE :qLike
        ORDER BY t.createdAt DESC
        """)
    Page<FindingTemplate> search(@Param("qLike") String qLike, Pageable pageable);
}
