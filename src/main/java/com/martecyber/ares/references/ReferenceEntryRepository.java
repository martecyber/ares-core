package com.martecyber.ares.references;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReferenceEntryRepository extends JpaRepository<ReferenceEntry, Long> {
    Page<ReferenceEntry> findByCatalogId(Long catalogId, Pageable pageable);
    Page<ReferenceEntry> findByCatalogIdAndTitleContainingIgnoreCase(Long catalogId, String title, Pageable pageable);
    List<ReferenceEntry> findByIdIn(List<Long> ids);
    java.util.Optional<ReferenceEntry> findByCatalogIdAndTitle(Long catalogId, String title);
    java.util.Optional<ReferenceEntry> findByCatalogIdAndUrl(Long catalogId, String url);

    @Query("SELECT r FROM ReferenceEntry r JOIN r.findings f WHERE f.id = :findingId")
    List<ReferenceEntry> findByFindingId(@Param("findingId") Long findingId);

    @Query("SELECT r FROM ReferenceEntry r JOIN r.findingTemplates t WHERE t.id = :templateId")
    List<ReferenceEntry> findByTemplateId(@Param("templateId") Long templateId);
}
