package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReportTemplateProjectTypeRepository extends JpaRepository<ReportTemplateProjectType, ReportTemplateProjectType.Id> {

    @Query("SELECT r FROM ReportTemplateProjectType r WHERE r.id.templateId = :templateId")
    List<ReportTemplateProjectType> findByTemplateId(@Param("templateId") Long templateId);

    @Modifying
    @Query("DELETE FROM ReportTemplateProjectType r WHERE r.id.templateId = :templateId")
    void deleteByTemplateId(@Param("templateId") Long templateId);
}
