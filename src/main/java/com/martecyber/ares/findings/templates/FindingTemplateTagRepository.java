package com.martecyber.ares.findings.templates;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

/** Join table between finding templates and the shared {@code tag} catalog. */
public interface FindingTemplateTagRepository extends CrudRepository<FindingTemplateTag, FindingTemplateTagId> {

    @Modifying
    @Query(value = """
        INSERT INTO ares.finding_template_tag (finding_template_id, tag_id, created_at)
        VALUES (:templateId, :tagId, NOW())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void assign(@Param("templateId") Long templateId, @Param("tagId") Long tagId);

    @Modifying
    @Query(value = "DELETE FROM ares.finding_template_tag WHERE finding_template_id = :templateId AND tag_id = :tagId", nativeQuery = true)
    void unassign(@Param("templateId") Long templateId, @Param("tagId") Long tagId);

    /** (finding_template_id, tag id/name/color) rows for a batch of templates — used to populate
     *  FindingTemplateDto.tags for a page without one query per row. */
    @Query(value = """
        SELECT ftt.finding_template_id AS templateId, t.id AS id, t.name AS name, t.color AS color
        FROM ares.finding_template_tag ftt
        JOIN ares.tag t ON t.id = ftt.tag_id
        WHERE ftt.finding_template_id IN :templateIds
        ORDER BY t.name ASC
        """, nativeQuery = true)
    List<FindingTemplateTagRow> findTagsForTemplateIds(@Param("templateIds") Collection<Long> templateIds);

    interface FindingTemplateTagRow {
        Long getTemplateId();
        Long getId();
        String getName();
        String getColor();
    }
}
