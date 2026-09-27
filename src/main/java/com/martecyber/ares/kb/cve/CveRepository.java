package com.martecyber.ares.kb.cve;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CveRepository extends JpaRepository<CveEntry, Long>, JpaSpecificationExecutor<CveEntry> {

    Optional<CveEntry> findByCveId(String cveId);

    List<CveEntry> findAllByCveIdIn(Collection<String> cveIds);

    /** Case-insensitive counterpart of {@link #findAllByCveIdIn} — needed by
     *  CveService.recomputeExploitCounts, whose keys come from ares.exploit.cve_ids (lowercase-
     *  normalized at write time, ExploitEntry.setCveIds) while CveEntry.cveId is stored as-received
     *  from the CVE feed (uppercase). Written as an explicit JPQL LOWER() comparison rather than
     *  relying on Spring Data's IgnoreCase+In derivation, to avoid any ambiguity about whether the
     *  IN collection's own elements get lowered the same way the property side does. */
    @Query("SELECT e FROM CveEntry e WHERE LOWER(e.cveId) IN :lowerCaseCveIds")
    List<CveEntry> findByCveIdIgnoreCaseIn(@Param("lowerCaseCveIds") Collection<String> lowerCaseCveIds);

    List<CveEntry> findByExploitCountGreaterThan(int value);

    Page<CveEntry> findBySeverityIgnoreCase(String severity, Pageable pageable);

    Page<CveEntry> findBySeverityIn(List<String> severities, Pageable pageable);

    /** Entries still flagged as KEV-listed from a previous sync but absent from the latest feed. */
    List<CveEntry> findByKevListedTrueAndCveIdNotIn(Collection<String> currentCveIds);

    Page<CveEntry> findByAnyKevListed(boolean anyKevListed, Pageable pageable);

    List<CveEntry> findByVulncheckKevListedTrueAndCveIdNotIn(Collection<String> currentCveIds);

    /** Same 3-field OR search as the old Mongo regex query (cveId/description/cwes) — cwes is a
     *  native array now, so matching it needs unnest rather than a regex-against-array. */
    @Query(value = """
        SELECT * FROM ares.cve
        WHERE cve_id ILIKE CONCAT('%', :kw, '%')
           OR description ILIKE CONCAT('%', :kw, '%')
           OR EXISTS (SELECT 1 FROM unnest(cwes) x WHERE x ILIKE CONCAT('%', :kw, '%'))
        """,
        countQuery = """
        SELECT count(*) FROM ares.cve
        WHERE cve_id ILIKE CONCAT('%', :kw, '%')
           OR description ILIKE CONCAT('%', :kw, '%')
           OR EXISTS (SELECT 1 FROM unnest(cwes) x WHERE x ILIKE CONCAT('%', :kw, '%'))
        """,
        nativeQuery = true)
    Page<CveEntry> search(@Param("kw") String keyword, Pageable pageable);

    Optional<CveEntry> findTopByOrderBySyncedAtDesc();

    long countBySeverityIgnoreCase(String severity);
}
