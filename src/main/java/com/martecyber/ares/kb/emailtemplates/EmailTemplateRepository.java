package com.martecyber.ares.kb.emailtemplates;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    Page<EmailTemplate> findAllByOrderByNameAsc(Pageable pageable);

    @Query("""
        SELECT t FROM EmailTemplate t
        WHERE (:q IS NULL OR LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY t.name ASC
        """)
    Page<EmailTemplate> search(@Param("q") String q, Pageable pageable);
}
