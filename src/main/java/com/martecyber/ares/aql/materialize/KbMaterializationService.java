package com.martecyber.ares.aql.materialize;

import com.martecyber.ares.kb.cve.CveEntry;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Maintains kb_materialized_ref (AQL implementation plan, V145/Phase 2) — lazily, on demand,
 * rather than mirroring the entire synced KB corpus. Only CVE is wired up for now (the only
 * catalog with hot AQL fields defined so far); extend {@link #materializeIfAbsent} /
 * {@link #refreshAllMaterialized} the same way for CWE/CAPEC/etc. if/when their own hot fields
 * get defined.
 */
@Service
public class KbMaterializationService {

    private static final String CVE_CATALOG_CODE = "CVE";

    private final KbMaterializedRefRepository repo;
    private final CveRepository cveRepo;
    private final ReferenceCatalogRepository catalogRepo;

    public KbMaterializationService(KbMaterializedRefRepository repo, CveRepository cveRepo,
                                     ReferenceCatalogRepository catalogRepo) {
        this.repo = repo;
        this.cveRepo = cveRepo;
        this.catalogRepo = catalogRepo;
    }

    /** Called when a new CVE reference is linked from Postgres (DetectionReferenceExtractor,
     *  and eventually any other entity that gains CVE references) — a no-op if a row already
     *  exists, or if the CVE isn't in the synced KB corpus yet. */
    @Transactional
    public void materializeIfAbsent(String code) {
        Optional<Long> catalogId = catalogRepo.findByCode(CVE_CATALOG_CODE).map(ReferenceCatalog::getId);
        if (catalogId.isEmpty()) return;
        KbMaterializedRefId id = new KbMaterializedRefId(catalogId.get(), code);
        if (repo.existsById(id)) return;
        cveRepo.findByCveId(code).ifPresent(cve -> {
            KbMaterializedRef row = new KbMaterializedRef();
            row.setId(id);
            applyFields(row, cve);
            repo.save(row);
        });
    }

    /** Refreshes every already-materialized CVE row from its current Mongo state — called at the
     *  end of each KEV/exploit sync job so materialized rows never drift stale. Never creates new
     *  rows (that's what {@link #materializeIfAbsent} is for) — a full-corpus mirror was
     *  deliberately ruled out (the CVE corpus is large; most of it is never queried). */
    @Transactional
    public void refreshAllMaterialized() {
        Optional<Long> catalogId = catalogRepo.findByCode(CVE_CATALOG_CODE).map(ReferenceCatalog::getId);
        if (catalogId.isEmpty()) return;
        List<KbMaterializedRef> existing = repo.findByIdCatalogId(catalogId.get());
        if (existing.isEmpty()) return;
        for (KbMaterializedRef row : existing) {
            cveRepo.findByCveId(row.getId().getCode()).ifPresent(cve -> applyFields(row, cve));
        }
        repo.saveAll(existing);
    }

    private void applyFields(KbMaterializedRef row, CveEntry cve) {
        row.setKevListed(cve.isAnyKevListed());
        row.setCvssScore(cve.getCvssScore() != null ? BigDecimal.valueOf(cve.getCvssScore()) : null);
        row.setSeverity(cve.getSeverity());
        row.setExploitCount(cve.getExploitCount());
        row.setSyncedAt(OffsetDateTime.now());
    }
}
