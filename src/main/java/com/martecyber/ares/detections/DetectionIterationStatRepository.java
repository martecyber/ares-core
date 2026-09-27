package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DetectionIterationStatRepository extends JpaRepository<DetectionIterationStat, Long> {

    boolean existsByProjectIdAndDetectionIdAndIterationLabelAndArea(
        Long projectId, Long detectionId, String iterationLabel, String area);

    /** Everything already recorded for a project — used to diff against history during backfill
     *  instead of one exists-query per historical event. */
    List<DetectionIterationStat> findByProjectId(Long projectId);

    /** Distinct iteration labels with at least one recorded stat for a project (ordered). */
    @Query("""
        SELECT DISTINCT s.iterationLabel FROM DetectionIterationStat s
        WHERE s.projectId = :projectId
        ORDER BY s.iterationLabel ASC
        """)
    List<String> findDistinctIterationLabels(@Param("projectId") Long projectId);

    /** Count of distinct detections per area for one iteration of a project. */
    @Query("""
        SELECT s.area AS area, COUNT(s) AS cnt
        FROM DetectionIterationStat s
        WHERE s.projectId = :projectId AND s.iterationLabel = :label
        GROUP BY s.area
        """)
    List<Object[]> countByProjectAndLabelGroupByArea(@Param("projectId") Long projectId, @Param("label") String label);
}
