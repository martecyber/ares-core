package com.martecyber.ares.kb.kev;

import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Syncs CISA's Known Exploited Vulnerabilities (KEV) catalog — a curated list of CVEs with
 * confirmed active exploitation. Complements the main {@code kb_cve} catalog rather than
 * replacing it: lookups are keyed by CVE ID so callers can flag any CVE reference as KEV-listed.
 * The actual sync work lives in {@link CisaKevSyncRunner} — see its javadoc for why.
 */
@Service
public class CisaKevService {

    static final String KEV_FEED_URL = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";
    private static final String SOURCE = CisaKevSyncRunner.SOURCE;

    private final CveKevDetailRepository repo;
    private final JobService jobService;
    private final CisaKevSyncRunner runner;

    public CisaKevService(CveKevDetailRepository repo, JobService jobService, CisaKevSyncRunner runner) {
        this.repo = repo;
        this.jobService = jobService;
        this.runner = runner;
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public Optional<CveKevDetail> findById(String id) {
        String cveId = id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase();
        return repo.findByCveIdAndSource(cveId, SOURCE);
    }

    public Page<CveKevDetail> findAll(Pageable p) { return repo.findBySource(SOURCE, p); }

    public Page<CveKevDetail> search(String keyword, Pageable p) { return repo.search(SOURCE, keyword, p); }

    /** Batch lookup used by the frontend to flag CVE references/list rows as KEV-listed. */
    public List<StatusDto> resolveStatus(List<String> rawIds) {
        List<String> normalized = rawIds.stream()
            .map(id -> id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase())
            .toList();
        return repo.findByCveIdInAndSource(normalized, SOURCE).stream()
            .map(e -> new StatusDto(e.getCveId(), e.getVulnerabilityName(), e.getDateAdded(), e.getDueDate(),
                e.isKnownRansomwareCampaignUse(), e.getRequiredAction()))
            .toList();
    }

    public record StatusDto(String id, String vulnerabilityName, LocalDate dateAdded, LocalDate dueDate,
                             boolean knownRansomwareCampaignUse, String requiredAction) {}

    public SyncStats stats() {
        long total = repo.countBySource(SOURCE);
        Optional<CveKevDetail> latest = repo.findTopBySourceOrderBySyncedAtDesc(SOURCE);
        Instant lastSync = latest.map(CveKevDetail::getSyncedAt).orElse(null);
        return new SyncStats(total, repo.countBySourceAndKnownRansomwareCampaignUseTrue(SOURCE), lastSync);
    }

    public record SyncStats(long total, long ransomware, Instant lastSync) {
        public boolean isSynced() { return total > 0; }
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /** Update: upserts onto whatever's already stored. Disabled in the UI until a first sync exists. */
    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_kev", null, null, null));
        runner.syncAsync(job.id(), false);
        return job.id();
    }

    /** Full download: wipes the existing KEV catalog first, then re-fetches from scratch. */
    public Long triggerFullSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_kev_full", null, null, null));
        runner.syncAsync(job.id(), true);
        return job.id();
    }
}
