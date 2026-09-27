package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

/** Join table between findings and the shared {@code tag} catalog. */
public interface FindingTagRepository extends CrudRepository<FindingTag, FindingTagId> {

    @Modifying
    @Query(value = """
        INSERT INTO ares.finding_tag (finding_id, tag_id, created_at)
        VALUES (:findingId, :tagId, NOW())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void assign(@Param("findingId") Long findingId, @Param("tagId") Long tagId);

    @Modifying
    @Query(value = "DELETE FROM ares.finding_tag WHERE finding_id = :findingId AND tag_id = :tagId", nativeQuery = true)
    void unassign(@Param("findingId") Long findingId, @Param("tagId") Long tagId);

    /** (finding_id, tag id/name/color) rows for a batch of findings — used to populate
     *  FindingDto.tags for a page without one query per row. */
    @Query(value = """
        SELECT ft.finding_id AS findingId, t.id AS id, t.name AS name, t.color AS color
        FROM ares.finding_tag ft
        JOIN ares.tag t ON t.id = ft.tag_id
        WHERE ft.finding_id IN :findingIds
        ORDER BY t.name ASC
        """, nativeQuery = true)
    List<FindingTagRow> findTagsForFindingIds(@Param("findingIds") Collection<Long> findingIds);

    interface FindingTagRow {
        Long getFindingId();
        Long getId();
        String getName();
        String getColor();
    }
}
