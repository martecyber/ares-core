package com.martecyber.ares.findings;

import com.martecyber.ares.affections.Affection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface FindingRepository extends JpaRepository<Finding, Long>, JpaSpecificationExecutor<Finding> {

    /**
     * includeDrafts must be explicitly true to return draft findings.
     * Drafts are only meant to be served from the project-scoped view.
     * {@code orgIds}, when non-null, restricts results to a caller-accessible set of orgs
     * (used for non-admin callers who didn't supply an explicit project/org filter).
     */
    @Query("""
        SELECT f FROM Finding f
        JOIN com.martecyber.ares.projects.Project p ON p.id = f.projectId
        WHERE (:projectId IS NULL OR f.projectId = :projectId)
          AND (:projectIds IS NULL OR f.projectId IN :projectIds)
          AND (:orgId IS NULL OR p.organizationId = :orgId)
          AND (:orgIds IS NULL OR p.organizationId IN :orgIds)
          AND (:includeDrafts = true OR f.isDraft = false)
          AND (:severities IS NULL OR f.severity IN :severities)
          AND (:statusIds IS NULL OR f.statusId IN :statusIds)
          AND (:iterationLabel IS NULL OR f.iterationLabel = :iterationLabel)
          AND (:qLike IS NULL OR LOWER(f.title) LIKE :qLike OR LOWER(f.code) LIKE :qLike)
        ORDER BY f.reportedAt ASC NULLS LAST, f.createdAt ASC
        """)
    Page<Finding> filter(
        @Param("projectId") Long projectId,
        @Param("projectIds") Collection<Long> projectIds,
        @Param("orgId") Long orgId,
        @Param("orgIds") Collection<Long> orgIds,
        @Param("includeDrafts") boolean includeDrafts,
        @Param("severities") Collection<String> severities,
        @Param("statusIds") Collection<Long> statusIds,
        @Param("iterationLabel") String iterationLabel,
        @Param("qLike") String qLike,
        Pageable p
    );

    long countByProjectId(Long projectId);

    @Query("SELECT COUNT(f) FROM Finding f WHERE f.projectId = :projectId AND f.isDraft = false")
    long countPublishedByProjectId(@Param("projectId") Long projectId);

    /** Counts published findings in a specific iteration (for sequential code within that iteration). */
    @Query("SELECT COUNT(f) FROM Finding f WHERE f.projectId = :projectId AND f.isDraft = false AND f.iterationLabel = :label")
    long countPublishedByProjectAndLabel(@Param("projectId") Long projectId, @Param("label") String label);

    @Query("SELECT f FROM Finding f WHERE f.projectId = :projectId AND f.isReadyToReport = true AND f.isDraft = false ORDER BY f.createdAt ASC")
    java.util.List<Finding> findReadyToReport(@Param("projectId") Long projectId);

    @Query(value = """
        SELECT f.* FROM ares.finding f
        LEFT JOIN (
            SELECT finding_id,
                   COALESCE(MAX(score) FILTER (WHERE is_default), MAX(score)) AS best_score
            FROM ares.finding_score GROUP BY finding_id
        ) fs ON fs.finding_id = f.id
        WHERE f.id IN :ids AND f.is_draft = true
        ORDER BY
            CASE f.severity
                WHEN 'critical' THEN 1
                WHEN 'high'     THEN 2
                WHEN 'medium'   THEN 3
                WHEN 'low'      THEN 4
                ELSE                 5
            END ASC,
            COALESCE(fs.best_score, 0) DESC
        """, nativeQuery = true)
    java.util.List<Finding> findDraftsByIdsOrderBySeverity(@Param("ids") java.util.List<Long> ids);

    /**
     * All published findings whose remediation is still open (used for SLA stats).
     * Remediation is considered closed only when every affection is closed;
     * findings with no affections are treated as open.
     */
    @Query("""
        SELECT f FROM Finding f
        JOIN Project e ON f.projectId = e.id
        WHERE e.organizationId = :orgId
          AND f.isDraft = false
          AND (
              NOT EXISTS (SELECT a FROM Affection a WHERE a.findingId = f.id)
              OR EXISTS  (SELECT a FROM Affection a WHERE a.findingId = f.id AND a.status = 'open')
          )
        ORDER BY f.reportedAt ASC NULLS LAST
        """)
    java.util.List<Finding> findOpenPublishedByOrgId(@Param("orgId") Long orgId);

    /** Findings linked to an asset (via affection) within a single project. */
    @Query("""
        SELECT DISTINCT f FROM Finding f
        JOIN Affection a ON a.findingId = f.id
        JOIN a.assetLinks aa
        WHERE aa.assetId = :assetId
          AND f.projectId = :projectId
          AND f.isDraft = false
        ORDER BY f.createdAt DESC
        """)
    java.util.List<Finding> findByAssetAndProject(
        @Param("assetId") Long assetId,
        @Param("projectId") Long projectId
    );

    /** Findings linked to an asset (via affection) across all projects of an organization. */
    @Query("""
        SELECT DISTINCT f FROM Finding f
        JOIN Project p ON p.id = f.projectId
        JOIN Affection a ON a.findingId = f.id
        JOIN a.assetLinks aa
        WHERE aa.assetId = :assetId
          AND p.organizationId = :orgId
          AND f.isDraft = false
        ORDER BY f.createdAt DESC
        """)
    java.util.List<Finding> findByAssetAndOrg(
        @Param("assetId") Long assetId,
        @Param("orgId") Long orgId
    );

    /**
     * Open published findings in an org whose due date is between today and today+daysBefore
     * (inclusive). Used by the messaging SLA scheduler to surface findings that are about to
     * miss their remediation window. "Open" = the finding's current status doesn't mean closed.
     */
    @Query("""
        SELECT f FROM Finding f
        JOIN Project p ON p.id = f.projectId
        JOIN FindingStatus s ON s.id = f.statusId
        WHERE p.organizationId = :orgId
          AND f.isDraft = false
          AND f.dueDate IS NOT NULL
          AND f.dueDate BETWEEN :today AND :horizon
          AND s.meansClosed = false
        ORDER BY f.dueDate ASC
        """)
    java.util.List<Finding> findDueSoonForOrg(
        @Param("orgId") Long orgId,
        @Param("today") java.time.LocalDate today,
        @Param("horizon") java.time.LocalDate horizon
    );

    @Query(value = """
        SELECT f.* FROM ares.finding f
        LEFT JOIN (
            SELECT finding_id,
                   COALESCE(MAX(score) FILTER (WHERE is_default), MAX(score)) AS best_score
            FROM ares.finding_score GROUP BY finding_id
        ) fs ON fs.finding_id = f.id
        WHERE f.project_id = :projectId AND f.is_draft = true AND f.status_id = :statusId
        ORDER BY
            CASE f.severity
                WHEN 'critical' THEN 1
                WHEN 'high'     THEN 2
                WHEN 'medium'   THEN 3
                WHEN 'low'      THEN 4
                ELSE                 5
            END ASC,
            COALESCE(fs.best_score, 0) DESC
        """, nativeQuery = true)
    java.util.List<Finding> findReadyToPublishOrderBySeverity(
        @Param("projectId") Long projectId,
        @Param("statusId") Long statusId
    );

    // ── MONITOR stats queries ─────────────────────────────────────────────────

    /** All published (non-draft) findings for a project, grouped by status name. */
    @Query("""
        SELECT s.name AS statusName, COUNT(f) AS cnt
        FROM Finding f
        JOIN FindingStatus s ON s.id = f.statusId
        WHERE f.projectId = :projectId AND f.isDraft = false
        GROUP BY s.name
        """)
    java.util.List<Object[]> countByProjectGroupByStatus(@Param("projectId") Long projectId);

    /** Published findings whose due_date has passed and are still in 'open' status. */
    @Query("""
        SELECT f FROM Finding f
        JOIN FindingStatus s ON s.id = f.statusId
        WHERE f.projectId = :projectId
          AND f.isDraft = false
          AND s.name = 'open'
          AND f.dueDate IS NOT NULL
          AND f.dueDate < :today
        ORDER BY f.dueDate ASC
        """)
    java.util.List<Finding> findOutOfSlaByProject(
        @Param("projectId") Long projectId,
        @Param("today") java.time.LocalDate today
    );

    /** Distinct iteration labels used by published findings in a project (ordered). */
    @Query("""
        SELECT DISTINCT f.iterationLabel FROM Finding f
        WHERE f.projectId = :projectId AND f.isDraft = false AND f.iterationLabel IS NOT NULL
        ORDER BY f.iterationLabel ASC
        """)
    java.util.List<String> findDistinctIterationLabels(@Param("projectId") Long projectId);

    /** Published findings whose iteration_label is lexicographically between from and to (inclusive). */
    @Query("""
        SELECT f FROM Finding f
        WHERE f.projectId = :projectId AND f.isDraft = false
          AND f.iterationLabel IS NOT NULL
          AND f.iterationLabel >= :fromLabel
          AND f.iterationLabel <= :toLabel
        ORDER BY f.iterationLabel ASC, f.reportedAt ASC
        """)
    java.util.List<Finding> findByProjectAndIterationRange(
        @Param("projectId") Long projectId,
        @Param("fromLabel") String fromLabel,
        @Param("toLabel") String toLabel
    );

    /** Count published findings by iteration label and status name. */
    @Query("""
        SELECT s.name AS statusName, COUNT(f) AS cnt
        FROM Finding f
        JOIN FindingStatus s ON s.id = f.statusId
        WHERE f.projectId = :projectId AND f.isDraft = false AND f.iterationLabel = :label
        GROUP BY s.name
        """)
    java.util.List<Object[]> countByProjectAndLabelGroupByStatus(
        @Param("projectId") Long projectId,
        @Param("label") String label
    );
}
