package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

    List<ReportTemplate> findByIsActiveTrueOrderByNameAsc();

    @Query(value = """
        SELECT rt.* FROM ares.report_template rt
        WHERE rt.is_active = TRUE
          AND (rt.is_generic = TRUE
               OR EXISTS (
                   SELECT 1 FROM ares.report_template_project_type rtet
                   WHERE rtet.template_id = rt.id
                     AND rtet.project_type_id = :typeId
               ))
        ORDER BY rt.name
        """, nativeQuery = true)
    List<ReportTemplate> findForProjectType(@Param("typeId") Long typeId);
}
