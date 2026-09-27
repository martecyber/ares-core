package com.martecyber.ares.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportFieldTypeRepository extends JpaRepository<ReportFieldType, Long> {
    List<ReportFieldType> findAllByOrderBySortOrderAscLabelAsc();
}
