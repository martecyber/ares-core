package com.martecyber.ares.kb.kev;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CveKevDetailRepository extends JpaRepository<CveKevDetail, Long> {

    Optional<CveKevDetail> findByCveIdAndSource(String cveId, String source);

    List<CveKevDetail> findByCveIdInAndSource(Collection<String> cveIds, String source);

    Page<CveKevDetail> findBySource(String source, Pageable pageable);

    Optional<CveKevDetail> findTopBySourceOrderBySyncedAtDesc(String source);

    long countBySource(String source);

    long countBySourceAndKnownRansomwareCampaignUseTrue(String source);

    /** Deletes only this source's rows — critical that this is NOT {@code deleteAll()} now that
     *  both KEV sources share one table; a CISA full-resync wiping VulnCheck's rows (or vice
     *  versa) would be a real correctness bug, not just a naming nit. {@code @Transactional} is
     *  required here (same fix as {@code AttackTechniqueTacticRepository}/{@code
     *  AttackTechniqueMitigationRepository.deleteByMatrix} earlier in this initiative) — called
     *  from {@code CisaKevSyncRunner}/{@code VulnCheckKevSyncRunner}'s own {@code @Async} methods,
     *  which don't carry a surrounding transaction, so this derived delete needs to open its own
     *  or fail with {@code TransactionRequiredException}. Caught live in production: both KEV sync
     *  jobs were failing on every {@code wipeFirst} run until this was added. */
    @Transactional
    void deleteBySource(String source);

    @Query("SELECT e FROM CveKevDetail e WHERE e.source = :source AND (" +
        "LOWER(e.cveId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(e.vulnerabilityName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(e.vendorProject) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(e.product) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<CveKevDetail> search(@Param("source") String source, @Param("keyword") String keyword, Pageable pageable);
}
