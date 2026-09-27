package com.martecyber.ares.kb.testing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestingProcedureRepository extends JpaRepository<TestingProcedure, Long> {

    Page<TestingProcedure> findAllByOrderByTitleAsc(Pageable pageable);

    @Query("""
        SELECT p FROM TestingProcedure p
        WHERE (:q IS NULL OR LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY p.title ASC
        """)
    Page<TestingProcedure> search(@Param("q") String q, Pageable pageable);

    /** Procedures linked to a given testing-guide point (for the project checklist). */
    @Query("""
        SELECT p FROM TestingProcedure p
        WHERE p.id IN (
            SELECT l.procedureId FROM TestingProcedureGuidePoint l WHERE l.guidePointId = :pointId
        )
        ORDER BY p.title ASC
        """)
    List<TestingProcedure> findByGuidePointId(@Param("pointId") Long pointId);
}
