package com.martecyber.ares.kb.kev;

import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Syncs VulnCheck's Known Exploited Vulnerabilities catalog — a community-tier, extended version
 * of CISA's KEV feed (more CVEs, plus exploitation-report/canary data). Unlike CISA's feed this
 * requires an API key: {@link #getApiKey()}/{@link #setApiKey} manage it, encrypted at rest via
 * the same {@link CredentialEncryptionService} used for tool-integration credentials — kept as a
 * standalone settings row rather than a full {@code Integration} row because that subsystem is
 * grant/capability-based (project-scoped scanning tools), which doesn't fit a platform-wide KB sync.
 * The actual sync work lives in {@link VulnCheckKevSyncRunner} — see its javadoc for why.
 */
@Service
public class VulnCheckKevService {

    static final String BACKUP_ENDPOINT = "https://api.vulncheck.com/v3/backup/vulncheck-kev";
    private static final String SOURCE = VulnCheckKevSyncRunner.SOURCE;

    private final CveKevDetailRepository repo;
    private final VulnCheckSettingsRepository settingsRepo;
    private final CredentialEncryptionService encryption;
    private final JobService jobService;
    private final VulnCheckKevSyncRunner runner;

    public VulnCheckKevService(CveKevDetailRepository repo, VulnCheckSettingsRepository settingsRepo,
                                CredentialEncryptionService encryption, JobService jobService,
                                VulnCheckKevSyncRunner runner) {
        this.repo = repo;
        this.settingsRepo = settingsRepo;
        this.encryption = encryption;
        this.jobService = jobService;
        this.runner = runner;
    }

    // ── API key management ───────────────────────────────────────────────

    public boolean hasApiKey() {
        return settingsRepo.findById(1).map(s -> s.getApiKey() != null).orElse(false);
    }

    public void setApiKey(String apiKey) {
        VulnCheckSettings s = settingsRepo.findById(1).orElseGet(VulnCheckSettings::new);
        CredentialEncryptionService.Encrypted enc = encryption.encrypt(apiKey);
        s.setApiKey(enc.ciphertext());
        s.setApiKeyIv(enc.iv());
        s.setUpdatedAt(OffsetDateTime.now());
        settingsRepo.save(s);
    }

    /** Public so other exploit-adjacent services (e.g. VulnCheckXdbSyncRunner) can reuse the
     *  same key — VulnCheck's KEV and XDB feeds share one account/token, so there's only ever
     *  one settings row rather than a duplicate key-entry UI per feature. */
    public Optional<String> getApiKey() {
        return settingsRepo.findById(1)
            .filter(s -> s.getApiKey() != null && s.getApiKeyIv() != null)
            .map(s -> encryption.decrypt(s.getApiKey(), s.getApiKeyIv()));
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public Optional<CveKevDetail> findByCve(String id) {
        String cveId = id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase();
        return repo.findByCveIdAndSource(cveId, SOURCE);
    }

    public Page<CveKevDetail> findAll(Pageable p) { return repo.findBySource(SOURCE, p); }

    public Page<CveKevDetail> search(String keyword, Pageable p) { return repo.search(SOURCE, keyword, p); }

    /** Batch lookup used by the frontend to flag CVE references/list rows as VulnCheck-KEV-listed. */
    public List<StatusDto> resolveStatus(List<String> rawIds) {
        List<String> normalized = rawIds.stream()
            .map(id -> id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase())
            .toList();
        Map<String, CveKevDetail> byCve = new HashMap<>();
        for (CveKevDetail e : repo.findByCveIdInAndSource(normalized, SOURCE)) byCve.put(e.getCveId(), e);
        List<StatusDto> result = new ArrayList<>();
        for (String id : normalized) {
            CveKevDetail e = byCve.get(id);
            if (e == null) continue;
            LocalDate dateAdded = e.getDateAdded() != null ? e.getDateAdded() : e.getCisaDateAdded();
            result.add(new StatusDto(id, e.getVulnerabilityName(), dateAdded, e.getDueDate(),
                e.isKnownRansomwareCampaignUse(), Boolean.TRUE.equals(e.getReportedExploitedByCanaries()), e.getRequiredAction()));
        }
        return result;
    }

    public record StatusDto(String id, String vulnerabilityName, LocalDate dateAdded, LocalDate dueDate,
                             boolean knownRansomwareCampaignUse, boolean reportedExploitedByCanaries,
                             String requiredAction) {}

    public SyncStats stats() {
        long total = repo.countBySource(SOURCE);
        Optional<CveKevDetail> latest = repo.findTopBySourceOrderBySyncedAtDesc(SOURCE);
        Instant lastSync = latest.map(CveKevDetail::getSyncedAt).orElse(null);
        return new SyncStats(total, repo.countBySourceAndKnownRansomwareCampaignUseTrue(SOURCE), lastSync, hasApiKey());
    }

    public record SyncStats(long total, long ransomware, Instant lastSync, boolean apiKeyConfigured) {
        public boolean isSynced() { return total > 0; }
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /** Update: upserts onto whatever's already stored. Disabled in the UI until a first sync exists. */
    public Long triggerSync() { return trigger("kb_sync_vulncheck_kev", false); }

    /** Full download: wipes the existing VulnCheck KEV catalog first, then re-fetches from scratch. */
    public Long triggerFullSync() { return trigger("kb_sync_vulncheck_kev_full", true); }

    private Long trigger(String jobType, boolean wipeFirst) {
        var job = jobService.create(new CreateJobRequest(jobType, null, null, null));
        Optional<String> apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            jobService.update(job.id(), new UpdateJobRequest("failed", null, null,
                "VulnCheck API key not configured — set it from the Sync dialog first."));
            return job.id();
        }
        runner.syncAsync(job.id(), wipeFirst, apiKey.get());
        return job.id();
    }
}
