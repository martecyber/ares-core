package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ReportFieldTemplateRepository extends JpaRepository<ReportFieldTemplate, Long> {
    List<ReportFieldTemplate> findByFieldTypeIdOrderByIsDefaultDescNameAsc(Long fieldTypeId);

    @Modifying
    @Query("UPDATE ReportFieldTemplate t SET t.isDefault = false WHERE t.fieldTypeId = :typeId AND t.id <> :id")
    void clearOtherDefaults(@Param("typeId") Long typeId, @Param("id") Long id);
}
