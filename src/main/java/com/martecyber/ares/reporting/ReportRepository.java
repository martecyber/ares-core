package com.martecyber.ares.reporting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

    @Query("""
        SELECT r FROM Report r WHERE
            (:orgId  IS NULL OR r.organizationId = :orgId)  AND
            (:orgIds IS NULL OR r.organizationId IN :orgIds) AND
            (:engId  IS NULL OR r.projectId   = :engId)  AND
            (:status IS NULL OR r.status         = :status)
        ORDER BY r.createdAt DESC
        """)
    Page<Report> filter(
        @Param("orgId")  Long organizationId,
        @Param("orgIds") java.util.Collection<Long> organizationIds,
        @Param("engId")  Long projectId,
        @Param("status") String status,
        Pageable pageable
    );
}
