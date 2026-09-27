package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ReportFieldRepository extends JpaRepository<ReportField, Long> {
    List<ReportField> findByReportId(Long reportId);

    @Modifying @Query("DELETE FROM ReportField f WHERE f.reportId = :reportId")
    void deleteByReportId(@Param("reportId") Long reportId);
}
