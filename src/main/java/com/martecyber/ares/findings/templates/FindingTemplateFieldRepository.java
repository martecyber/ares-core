package com.martecyber.ares.findings.templates;

import com.martecyber.ares.findings.FindingFieldType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FindingTemplateFieldRepository extends JpaRepository<FindingTemplateField, Long> {

    /** Ordered by the field type's configured sort_order (admin-defined), not insertion order. */
    @Query("""
        SELECT f FROM FindingTemplateField f
        JOIN FindingFieldType t ON t.id = f.typeId
        WHERE f.templateId = :templateId
        ORDER BY t.sortOrder ASC, t.title ASC
        """)
    List<FindingTemplateField> findByTemplateId(@Param("templateId") Long templateId);

    void deleteByTemplateId(Long templateId);
}
