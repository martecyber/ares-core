package com.martecyber.ares.detections;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

/** Join table between detections and the shared {@code tag} catalog. */
public interface DetectionTagRepository extends CrudRepository<DetectionTag, DetectionTagId> {

    @Modifying
    @Query(value = """
        INSERT INTO ares.detection_tag (detection_id, tag_id, created_at)
        VALUES (:detectionId, :tagId, NOW())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void assign(@Param("detectionId") Long detectionId, @Param("tagId") Long tagId);

    @Modifying
    @Query(value = "DELETE FROM ares.detection_tag WHERE detection_id = :detectionId AND tag_id = :tagId", nativeQuery = true)
    void unassign(@Param("detectionId") Long detectionId, @Param("tagId") Long tagId);

    /** (detection_id, tag id/name/color) rows for a batch of detections — used to populate
     *  DetectionDto.tags for a page without one query per row. */
    @Query(value = """
        SELECT dt.detection_id AS detectionId, t.id AS id, t.name AS name, t.color AS color
        FROM ares.detection_tag dt
        JOIN ares.tag t ON t.id = dt.tag_id
        WHERE dt.detection_id IN :detectionIds
        ORDER BY t.name ASC
        """, nativeQuery = true)
    List<DetectionTagRow> findTagsForDetectionIds(@Param("detectionIds") Collection<Long> detectionIds);

    interface DetectionTagRow {
        Long getDetectionId();
        Long getId();
        String getName();
        String getColor();
    }
}
