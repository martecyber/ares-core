package com.martecyber.ares.kb.thirdparty;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KbThirdPartyEntryRepository extends JpaRepository<KbThirdPartyEntry, Long> {

    List<KbThirdPartyEntry> findAllByEnabledTrue();

    @Query(value = """
        SELECT * FROM ares.kb_third_party_entry
        WHERE (:category IS NULL OR category = :category)
          AND (:kind     IS NULL OR kind     = :kind)
          AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
          AND (:q IS NULL OR (
              LOWER(value) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(COALESCE(notes, '')) LIKE LOWER(CONCAT('%', :q, '%'))
          ))
        """,
        countQuery = """
        SELECT COUNT(*) FROM ares.kb_third_party_entry
        WHERE (:category IS NULL OR category = :category)
          AND (:kind     IS NULL OR kind     = :kind)
          AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
          AND (:q IS NULL OR (
              LOWER(value) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(COALESCE(notes, '')) LIKE LOWER(CONCAT('%', :q, '%'))
          ))
        """,
        nativeQuery = true)
    Page<KbThirdPartyEntry> filter(
            @Param("category") String category,
            @Param("kind")     String kind,
            @Param("enabled")  Boolean enabled,
            @Param("q")        String q,
            Pageable pageable);
}
