package com.martecyber.ares.projects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Lightweight org-ownership lookup, used for org-access checks without loading the full entity. */
    @Query("SELECT p.organizationId FROM Project p WHERE p.id = :id")
    Optional<Long> findOrganizationIdById(@Param("id") Long id);

    /** Lightweight type-code lookup, used by {@code ProjectFacade#getProjectTypeCode}. */
    @Query(value = "SELECT t.code FROM ares.project e JOIN ares.project_type t ON e.type_id = t.id " +
                   "WHERE e.id = :projectId", nativeQuery = true)
    Optional<String> findTypeCodeById(@Param("projectId") Long projectId);

    // Native query so null status param and CASE-based filtering works reliably in PostgreSQL
    @Query(value = """
        SELECT * FROM ares.project WHERE
            (:orgId IS NULL OR organization_id = :orgId) AND
            (
                CAST(:status AS TEXT) IS NULL OR
                (CAST(:status AS TEXT) = 'completed' AND completed_at IS NOT NULL) OR
                (CAST(:status AS TEXT) = 'scheduled' AND completed_at IS NULL AND (start_date IS NULL OR start_date > CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'active'    AND completed_at IS NULL AND start_date IS NOT NULL AND start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'past_due'  AND completed_at IS NULL AND end_date IS NOT NULL AND end_date < CURRENT_DATE)
            )
        ORDER BY created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM ares.project WHERE
            (:orgId IS NULL OR organization_id = :orgId) AND
            (
                CAST(:status AS TEXT) IS NULL OR
                (CAST(:status AS TEXT) = 'completed' AND completed_at IS NOT NULL) OR
                (CAST(:status AS TEXT) = 'scheduled' AND completed_at IS NULL AND (start_date IS NULL OR start_date > CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'active'    AND completed_at IS NULL AND start_date IS NOT NULL AND start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'past_due'  AND completed_at IS NULL AND end_date IS NOT NULL AND end_date < CURRENT_DATE)
            )
        """,
        nativeQuery = true)
    Page<Project> filter(@Param("orgId") Long organizationId,
                            @Param("status") String status,
                            Pageable pageable);

    /** Same status filtering as {@link #filter}, but restricted to a caller-accessible set of orgs (non-admin). */
    @Query(value = """
        SELECT * FROM ares.project WHERE
            organization_id IN (:orgIds) AND
            (
                CAST(:status AS TEXT) IS NULL OR
                (CAST(:status AS TEXT) = 'completed' AND completed_at IS NOT NULL) OR
                (CAST(:status AS TEXT) = 'scheduled' AND completed_at IS NULL AND (start_date IS NULL OR start_date > CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'active'    AND completed_at IS NULL AND start_date IS NOT NULL AND start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'past_due'  AND completed_at IS NULL AND end_date IS NOT NULL AND end_date < CURRENT_DATE)
            )
        ORDER BY created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM ares.project WHERE
            organization_id IN (:orgIds) AND
            (
                CAST(:status AS TEXT) IS NULL OR
                (CAST(:status AS TEXT) = 'completed' AND completed_at IS NOT NULL) OR
                (CAST(:status AS TEXT) = 'scheduled' AND completed_at IS NULL AND (start_date IS NULL OR start_date > CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'active'    AND completed_at IS NULL AND start_date IS NOT NULL AND start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)) OR
                (CAST(:status AS TEXT) = 'past_due'  AND completed_at IS NULL AND end_date IS NOT NULL AND end_date < CURRENT_DATE)
            )
        """,
        nativeQuery = true)
    Page<Project> filterByOrgIds(@Param("orgIds") List<Long> organizationIds,
                                  @Param("status") String status,
                                  Pageable pageable);

    @Query("SELECT COUNT(e) FROM Project e WHERE " +
           "e.organizationId = :orgId AND EXTRACT(YEAR FROM e.createdAt) = :year")
    long countByOrgYear(@Param("orgId") Long orgId, @Param("year") int year);

    /** Sequence counter for Bug Hunting codes (no year component). */
    @Query(value = """
        SELECT COUNT(e.id) FROM ares.project e
        JOIN ares.project_type t ON e.type_id = t.id
        WHERE e.organization_id = :orgId AND t.code = :typeCode
        """, nativeQuery = true)
    long countByOrgAndTypeCode(@Param("orgId") Long orgId, @Param("typeCode") String typeCode);

    /**
     * Counts all MONITOR-type projects for an org regardless of subtype, so that
     * generated codes like ACME-MONITOR-01 are sequential across the whole MONITOR family.
     */
    @Query(value = """
        SELECT COUNT(e.id) FROM ares.project e
        JOIN ares.project_type t ON e.type_id = t.id
        LEFT JOIN ares.project_type sup ON t.supertype_id = sup.id
        WHERE e.organization_id = :orgId
          AND (t.code = 'MONITOR' OR sup.code = 'MONITOR')
        """, nativeQuery = true)
    long countByOrgMonitorType(@Param("orgId") Long orgId);

    /** 10 most recently started, non-completed projects for the org dashboard. */
    @Query(value = """
        SELECT * FROM ares.project
        WHERE organization_id = :orgId
          AND completed_at IS NULL
          AND (start_date IS NULL OR start_date <= CURRENT_DATE)
        ORDER BY start_date DESC NULLS LAST
        LIMIT 10
        """, nativeQuery = true)
    List<Project> findActiveByOrg(@Param("orgId") Long orgId);

    /** Projects that overlap [rangeStart, rangeEnd] (null dates = open-ended). Includes completed and past_due. */
    @Query(value = """
        SELECT * FROM ares.project
        WHERE (start_date IS NULL OR start_date <= :rangeEnd)
          AND (end_date   IS NULL OR end_date   >= :rangeStart)
        ORDER BY start_date ASC NULLS LAST
        """, nativeQuery = true)
    List<Project> findInRange(@Param("rangeStart") LocalDate rangeStart,
                                 @Param("rangeEnd")   LocalDate rangeEnd);
}
