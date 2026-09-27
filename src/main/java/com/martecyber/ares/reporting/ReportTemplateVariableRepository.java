package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportTemplateVariableRepository extends JpaRepository<ReportTemplateVariable, Long> {
    List<ReportTemplateVariable> findByTemplateId(Long templateId);
    void deleteByTemplateId(Long templateId);
}
