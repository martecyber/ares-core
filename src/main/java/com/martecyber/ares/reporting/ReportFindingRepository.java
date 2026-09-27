package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ReportFindingRepository extends JpaRepository<ReportFinding, ReportFindingId> {
    List<ReportFinding> findByReportId(Long reportId);

    @Modifying @Query("DELETE FROM ReportFinding rf WHERE rf.id.reportId = :reportId")
    void deleteByReportId(@Param("reportId") Long reportId);
}
