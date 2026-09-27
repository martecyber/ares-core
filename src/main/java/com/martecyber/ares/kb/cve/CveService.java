package com.martecyber.ares.kb.cve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.martecyber.ares.jobs.JobCancelledException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Intermediate representation of a CVSS score candidate during parsing.
// Assembled into CveEntry.CvssScore after determining which one is the default.
record CvssCandidate(String source, String role, String version, Double score, String vector, String severity) {}

@Service
public class CveService {

    private static final Logger log = LoggerFactory.getLogger(CveService.class);

    @Value("${ares.kb.cve.repo-url}")
    private String repoUrl;

    @Value("${ares.kb.cve.repo-path}")
    private String repoPathStr;

    private final CveRepository repo;
    private final CveSyncStateRepository stateRepo;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    // Phase 6 of the AQL-wide initiative migrated exploits to Postgres too — recomputeExploitCounts
    // now aggregates via a plain repository query instead of a Mongo aggregation, so this is a
    // JpaRepository like everything else CveService depends on, not a cross-service call (avoids
    // a circular bean: ExploitService itself depends on CveService).
    private final com.martecyber.ares.kb.exploits.ExploitRepository exploitRepo;
    private final com.martecyber.ares.aql.materialize.KbMaterializationService materializationService;
    private final CveAqlRegistry aqlRegistry;
    private final WorkflowEventDispatcher workflowEventDispatcher;

    public CveService(CveRepository repo, CveSyncStateRepository stateRepo,
                      JobService jobService, ObjectMapper objectMapper,
                      com.martecyber.ares.kb.exploits.ExploitRepository exploitRepo,
                      com.martecyber.ares.aql.materialize.KbMaterializationService materializationService,
                      CveAqlRegistry aqlRegistry, WorkflowEventDispatcher workflowEventDispatcher) {
        this.repo = repo;
        this.stateRepo = stateRepo;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.exploitRepo = exploitRepo;
        this.materializationService = materializationService;
        this.aqlRegistry = aqlRegistry;
        this.workflowEventDispatcher = workflowEventDispatcher;
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public Optional<CveEntry> findById(String cveId) {
        String normalized = cveId.toUpperCase().startsWith("CVE-") ? cveId.toUpperCase() : "CVE-" + cveId.toUpperCase();
        return repo.findByCveId(normalized);
    }

    public List<SeverityDto> resolveSeverities(List<String> rawIds) {
        List<String> normalized = rawIds.stream()
            .map(id -> id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase())
            .toList();
        return repo.findAllByCveIdIn(normalized).stream()
            .map(e -> new SeverityDto(e.getCveId(), e.getSeverity()))
            .toList();
    }

    public record SeverityDto(String id, String severity) {}

    /** Bulk description lookup — mirrors {@link #resolveSeverities}. Used to show a truncated
     *  CVE description as the reference row's title wherever a CVE is rendered as a bare id
     *  list (e.g. a CWE's Observed Examples), the same text ReferencesDialog.vue stores at
     *  attach-time for a manually-added Finding reference. */
    public List<DescriptionDto> resolveDescriptions(List<String> rawIds) {
        List<String> normalized = rawIds.stream()
            .map(id -> id.toUpperCase().startsWith("CVE-") ? id.toUpperCase() : "CVE-" + id.toUpperCase())
            .toList();
        return repo.findAllByCveIdIn(normalized).stream()
            .map(e -> new DescriptionDto(e.getCveId(), e.getDescription()))
            .toList();
    }

    public record DescriptionDto(String id, String description) {}

    public Page<CveEntry> findAll(Pageable p) { return repo.findAll(p); }

    /** AQL-driven listing (AQL implementation plan, Phase 2) — unlike findByKevSources/
     *  findByPocStatus/severity filters, an AQL query can AND/OR arbitrary field combinations
     *  together instead of applying only one filter dimension at a time. Postgres-backed since
     *  Phase 3 of the AQL-wide initiative — no more Mongo round trip for direct CVE queries. */
    public Page<CveEntry> findByAql(String aql, Pageable p) {
        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        var spec = new PostgresSpecificationCompiler<>(aqlRegistry).compile(node);
        return repo.findAll(spec, p);
    }

    public Page<CveEntry> findBySeverity(String severity, Pageable p) {
        return repo.findBySeverityIgnoreCase(severity, p);
    }

    public Page<CveEntry> findBySeverityIn(List<String> severities, Pageable p) {
        return repo.findBySeverityIn(severities, p);
    }

    public Page<CveEntry> search(String keyword, Pageable p) { return repo.search(keyword, p); }

    public Page<CveEntry> findByAnyKevListed(boolean anyKevListed, Pageable p) {
        return repo.findByAnyKevListed(anyKevListed, p);
    }

    /**
     * Filters by KEV source membership. {@code sources} may contain any of "cisa", "vulncheck",
     * "none" — results are the union (OR) of the selected criteria, e.g. ["cisa","vulncheck"]
     * returns everything listed in either catalog.
     */
    public Page<CveEntry> findByKevSources(List<String> sources, Pageable p) {
        List<Specification<CveEntry>> ors = new ArrayList<>();
        if (sources.contains("cisa"))      ors.add((root, q, cb) -> cb.isTrue(root.get("kevListed")));
        if (sources.contains("vulncheck")) ors.add((root, q, cb) -> cb.isTrue(root.get("vulncheckKevListed")));
        if (sources.contains("none"))      ors.add((root, q, cb) -> cb.and(
            cb.isFalse(root.get("kevListed")), cb.isFalse(root.get("vulncheckKevListed"))));
        if (ors.isEmpty()) return findAll(p);
        return repo.findAll(ors.stream().reduce(Specification::or).orElseThrow(), p);
    }

    /**
     * Filters by whether a CVE has any exploit/PoC mapped to it. {@code statuses} may contain
     * "yes" and/or "no" — same OR-of-selected-options shape as {@link #findByKevSources}.
     */
    public Page<CveEntry> findByPocStatus(List<String> statuses, Pageable p) {
        List<Specification<CveEntry>> ors = new ArrayList<>();
        if (statuses.contains("yes")) ors.add((root, q, cb) -> cb.greaterThan(root.get("exploitCount"), 0));
        if (statuses.contains("no"))  ors.add((root, q, cb) -> cb.equal(root.get("exploitCount"), 0));
        if (ors.isEmpty()) return findAll(p);
        return repo.findAll(ors.stream().reduce(Specification::or).orElseThrow(), p);
    }

    /**
     * Denormalizes the CISA KEV catalog onto matching {@link CveEntry} rows so the CVE list can
     * be sorted/filtered by KEV status without a cross-table join. Called by CisaKevService
     * after every sync with the full current KEV snapshot (CVE ID → date added).
     */
    public void updateKevFlags(Map<String, LocalDate> currentKev) {
        List<CveEntry> stale = repo.findByKevListedTrueAndCveIdNotIn(currentKev.keySet());
        for (CveEntry e : stale) {
            e.setKevListed(false);
            e.setKevDateAdded(null);
            e.setAnyKevListed(e.isVulncheckKevListed());
        }
        if (!stale.isEmpty()) repo.saveAll(stale);

        // Single bulk fetch instead of one findByCveId() round-trip per entry — with feeds in
        // the thousands, the per-entry version turned this into a silent, minutes-long stall
        // between the "parsing" and "completed" progress steps.
        List<CveEntry> matches = repo.findAllByCveIdIn(currentKev.keySet());
        List<CveEntry> updated = new ArrayList<>();
        List<CveEntry> newlyListed = new ArrayList<>();
        for (CveEntry e : matches) {
            LocalDate value = currentKev.get(e.getCveId());
            boolean wasListed = e.isKevListed();
            if (!wasListed || !Objects.equals(value, e.getKevDateAdded())) {
                e.setKevListed(true);
                e.setKevDateAdded(value);
                e.setAnyKevListed(true);
                updated.add(e);
                if (!wasListed) newlyListed.add(e);
            }
        }
        if (!updated.isEmpty()) repo.saveAll(updated);
        materializationService.refreshAllMaterialized();

        // stale + updated are already exactly the changed subset — a genuine "this CVE's KEV
        // status just flipped" signal, not "is currently KEV-listed" (which would also fire on
        // every unrelated later update to an already-KEV-listed CVE). newlyListed is the strict
        // false→true subset of updated (excludes a same-listing date correction), which is what
        // "added to KEV catalog" means — cve.kev_added fires only for those, cve.updated for all.
        for (CveEntry e : stale) workflowEventDispatcher.onCveUpdated(e);
        for (CveEntry e : updated) workflowEventDispatcher.onCveUpdated(e);
        for (CveEntry e : newlyListed) workflowEventDispatcher.onCveKevAdded(e, "cisa");
    }

    /** Same denormalization as {@link #updateKevFlags}, from VulnCheck's KEV catalog instead. */
    public void updateVulnCheckKevFlags(Map<String, LocalDate> currentKev) {
        List<CveEntry> stale = repo.findByVulncheckKevListedTrueAndCveIdNotIn(currentKev.keySet());
        for (CveEntry e : stale) {
            e.setVulncheckKevListed(false);
            e.setVulncheckKevDateAdded(null);
            e.setAnyKevListed(e.isKevListed());
        }
        if (!stale.isEmpty()) repo.saveAll(stale);

        List<CveEntry> matches = repo.findAllByCveIdIn(currentKev.keySet());
        List<CveEntry> updated = new ArrayList<>();
        List<CveEntry> newlyListed = new ArrayList<>();
        for (CveEntry e : matches) {
            LocalDate value = currentKev.get(e.getCveId());
            boolean wasListed = e.isVulncheckKevListed();
            if (!wasListed || !Objects.equals(value, e.getVulncheckKevDateAdded())) {
                e.setVulncheckKevListed(true);
                e.setVulncheckKevDateAdded(value);
                e.setAnyKevListed(true);
                updated.add(e);
                if (!wasListed) newlyListed.add(e);
            }
        }
        if (!updated.isEmpty()) repo.saveAll(updated);
        materializationService.refreshAllMaterialized();

        for (CveEntry e : stale) workflowEventDispatcher.onCveUpdated(e);
        for (CveEntry e : updated) workflowEventDispatcher.onCveUpdated(e);
        for (CveEntry e : newlyListed) workflowEventDispatcher.onCveKevAdded(e, "vulncheck");
    }

    /**
     * Adjusts {@code exploitCount} on the given CVEs by {@code delta} (+1 when an exploit is
     * added, -1 when removed) — called by ExploitService on single create/delete. Bulk-fetches
     * all affected entries in one query rather than looping {@code findByCveId} per ID (that
     * per-key pattern caused a multi-minute silent stall in the KEV sync — see updateKevFlags's
     * history — so every CVE-flag update path here goes through findAllByCveIdIn instead).
     */
    public void adjustExploitCount(Collection<String> cveIds, int delta) {
        if (cveIds == null || cveIds.isEmpty()) return;
        // Callers pass either caller-supplied uppercase IDs (createFromZip, ExploitGitRunner) or
        // ExploitEntry.getCveIds()'s lowercase-normalized values (ExploitService.delete) — same
        // case mismatch as recomputeExploitCounts, so the lookup has to be case-insensitive here too.
        Set<String> lowerCaseCveIds = cveIds.stream().map(id -> id.toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.toSet());
        List<CveEntry> matches = repo.findByCveIdIgnoreCaseIn(lowerCaseCveIds);
        for (CveEntry e : matches) {
            e.setExploitCount(Math.max(0, e.getExploitCount() + delta));
        }
        if (!matches.isEmpty()) repo.saveAll(matches);
        materializationService.refreshAllMaterialized();

        // delta is always ±1 (see doc above) — every match's count genuinely changed, no
        // before/after check needed. This is what "a CVE just got a new PoC" resolves to:
        // downstream, a CONDITION/template checking trigger.entity.exploitCount > 0.
        for (CveEntry e : matches) workflowEventDispatcher.onCveUpdated(e);
    }

    /**
     * Full recompute of {@code exploitCount} via a single {@code unnest+GROUP BY} over
     * {@code ares.exploit} (Postgres since Phase 6 of the AQL-wide initiative — replaces the old
     * Mongo {@code unwind+group} aggregation) — used after a bulk sync (ExploitDB, VulnCheck XDB)
     * touches many exploits at once, where one aggregate pass is cheaper than one
     * adjustExploitCount() call per newly-added exploit.
     *
     * <p>{@code ares.exploit.cve_ids} is lowercase-normalized at write time (like every other
     * HAS-enabled array in this initiative — see ExploitEntry.setCveIds), while
     * {@code ares.cve.cve_id} is stored as-received from the feed (uppercase) — so the match
     * against CveEntry has to go through {@link CveRepository#findByCveIdIgnoreCaseIn}, not the
     * plain {@link CveRepository#findAllByCveIdIn} every other caller in this class uses.
     */
    public void recomputeExploitCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : exploitRepo.countGroupedByCveId()) {
            String cveId = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (cveId != null) counts.put(cveId, (int) count);
        }

        Map<String, CveEntry> toSave = new LinkedHashMap<>();
        for (CveEntry e : repo.findByExploitCountGreaterThan(0)) {
            if (!counts.containsKey(e.getCveId().toLowerCase(java.util.Locale.ROOT))) {
                e.setExploitCount(0);
                toSave.put(e.getCveId(), e);
            }
        }
        for (CveEntry e : repo.findByCveIdIgnoreCaseIn(counts.keySet())) {
            Integer c = counts.get(e.getCveId().toLowerCase(java.util.Locale.ROOT));
            if (c != null && e.getExploitCount() != c) {
                e.setExploitCount(c);
                toSave.put(e.getCveId(), e);
            }
        }
        if (!toSave.isEmpty()) repo.saveAll(toSave.values());
        materializationService.refreshAllMaterialized();

        // toSave is already exactly the changed subset.
        for (CveEntry e : toSave.values()) workflowEventDispatcher.onCveUpdated(e);
    }

    public SyncStats stats() {
        long total = repo.count();
        Optional<CveEntry> latest = repo.findTopByOrderBySyncedAtDesc();
        Instant lastSync = latest.map(CveEntry::getSyncedAt).orElse(null);
        String lastCommit = stateRepo.findById("singleton").map(CveSyncState::getLastCommit).orElse(null);
        return new SyncStats(total,
            repo.countBySeverityIgnoreCase("CRITICAL"),
            repo.countBySeverityIgnoreCase("HIGH"),
            lastSync,
            lastCommit);
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_cve", null, null, null));
        syncAsync(job.id(), false);
        return job.id();
    }

    public Long triggerFullSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_cve_full", null, null, null));
        syncAsync(job.id(), true);
        return job.id();
    }

    @Async
    public void syncAsync(Long jobId, boolean forceFullScan) {
        setStatus(jobId, "running", 0);
        try {
            Path repoPath = Path.of(repoPathStr);
            boolean initialClone = !Files.exists(repoPath.resolve(".git"));

            String oldCommit = null;

            if (initialClone) {
                log.info("CVE sync job={}: cloning {} -> {}", jobId, repoUrl, repoPath);
                setStatus(jobId, null, 2);
                Files.createDirectories(repoPath.getParent());
                git(null, "clone", "--branch", "main", "--single-branch", "--", repoUrl, repoPath.toString());
            } else {
                if (!forceFullScan) {
                    oldCommit = stateRepo.findById("singleton")
                        .map(CveSyncState::getLastCommit)
                        .orElse(null);
                }
                log.info("CVE sync job={}: fetching (full={}), oldCommit={}", jobId, forceFullScan, oldCommit);
                setStatus(jobId, null, 2);
                clearGitLocks(repoPath);
                git(repoPath, "fetch", "origin", "main");
                git(repoPath, "reset", "--hard", "origin/main");
            }

            String newCommit = git(repoPath, "rev-parse", "HEAD").trim();
            log.info("CVE sync job={}: HEAD={}", jobId, newCommit);

            Instant now = Instant.now();
            long processed;

            if (forceFullScan || initialClone || oldCommit == null) {
                processed = fullScan(repoPath, jobId, now);
            } else if (oldCommit.equals(newCommit)) {
                log.info("CVE sync job={}: already up to date", jobId);
                processed = 0;
            } else {
                processed = deltaScan(repoPath, oldCommit, newCommit, jobId, now);
            }

            CveSyncState state = stateRepo.findById("singleton").orElse(new CveSyncState());
            state.setLastCommit(newCommit);
            state.setLastSyncedAt(now);
            state.setTotalProcessed(repo.count());
            stateRepo.save(state);

            String result = objectMapper.writeValueAsString(Map.of("processed", processed, "commit", newCommit));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, result, null));
            log.info("CVE sync completed job={} processed={} commit={}", jobId, processed, newCommit);

        } catch (JobCancelledException jce) {
            log.info("CVE sync job={} was cancelled — stopping", jobId);
        } catch (Exception ex) {
            log.error("CVE sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    // ── Full scan (initial clone) ──────────────────────────────────────────

    private long fullScan(Path repoPath, Long jobId, Instant syncedAt) throws IOException {
        Path cvesDir = repoPath.resolve("cves");
        if (!Files.isDirectory(cvesDir)) {
            log.warn("CVE sync job={}: 'cves' directory not found in repo", jobId);
            return 0;
        }

        log.info("CVE sync job={}: starting full scan of {}", jobId, cvesDir);
        setStatus(jobId, null, 5);

        List<Path> jsonFiles;
        try (Stream<Path> walk = Files.walk(cvesDir)) {
            jsonFiles = walk
                .filter(p -> p.getFileName().toString().startsWith("CVE-") && p.toString().endsWith(".json"))
                .toList();
        }

        return parseAndBulkUpsert(jsonFiles, jobId, syncedAt, 5000);
    }

    // ── Delta scan (git diff) ──────────────────────────────────────────────

    private long deltaScan(Path repoPath, String oldCommit, String newCommit, Long jobId, Instant syncedAt) throws IOException, InterruptedException {
        setStatus(jobId, null, 5);
        String changedFiles = git(repoPath, "diff", "--name-only", oldCommit, newCommit);

        List<Path> files = new ArrayList<>();
        for (String line : changedFiles.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("cves/") || !trimmed.endsWith(".json")) continue;
            Path file = repoPath.resolve(trimmed);
            if (Files.exists(file)) files.add(file);
        }

        long saved = parseAndBulkUpsert(files, jobId, syncedAt, 500);
        log.info("CVE delta scan: {} files changed, {} upserted", files.size(), saved);
        return saved;
    }

    // ── Shared parse + write loop ────────────────────────────────────────────

    /** Batches writes for the same reason the old Mongo bulk-write version did — 500 keeps
     *  memory bounded and progress responsive while still cutting round-trips drastically versus
     *  a naive per-file find-then-save loop. */
    private static final int BULK_BATCH_SIZE = 500;

    private long parseAndBulkUpsert(List<Path> files, Long jobId, Instant syncedAt, int cancelCheckEvery) {
        long total = files.size();
        if (total == 0) return 0;

        List<CveEntry> batch = new ArrayList<>(BULK_BATCH_SIZE);
        long saved = 0;
        long done = 0;

        for (Path file : files) {
            try {
                CveEntry entry = parseFile(file, syncedAt);
                if (entry != null) batch.add(entry);
            } catch (Exception e) {
                log.debug("CVE parse error {}: {}", file.getFileName(), e.getMessage());
            }

            if (batch.size() >= BULK_BATCH_SIZE) {
                saved += flushBatch(batch);
                batch.clear();
            }

            done++;
            if (done % cancelCheckEvery == 0) {
                if (jobService.isCancelled(jobId)) throw new JobCancelledException(jobId);
                int progress = 5 + (int) (done * 90.0 / Math.max(total, 1));
                setStatus(jobId, null, Math.min(progress, 94));
                log.debug("CVE scan progress: {}/{}", done, total);
            }
        }
        if (!batch.isEmpty()) saved += flushBatch(batch);

        return saved;
    }

    /**
     * Bulk upsert-by-{@code cveId}: fetch every existing row the batch could touch in one query,
     * then either save a freshly-parsed entry as-is (new CVE) or copy the parsed fields onto the
     * existing row (preserving its DB id — and, deliberately, its kevListed/vulncheckKevListed/
     * anyKevListed/kevDateAdded/vulncheckKevDateAdded/exploitCount, none of which parseFile ever
     * sets and none of which this sync owns; those belong to updateKevFlags/
     * updateVulnCheckKevFlags/adjustExploitCount/recomputeExploitCounts respectively). Same
     * bulk-fetch-before-write shape as updateKevFlags/adjustExploitCount, this time to know which
     * entries in the batch are genuinely new versus changed versus an unchanged re-sync of
     * identical upstream data — {@code lastModifiedAt} is parsed straight from the CVE source's
     * own {@code cveMetadata.dateUpdated} (see parseFile), a real upstream-versioned timestamp, so
     * comparing it against what was already stored is a correct, cheap per-document diff with no
     * extra parsing/hashing needed. One extra query per 500-entry batch — the same acceptable cost
     * this class already pays in updateKevFlags/adjustExploitCount/recomputeExploitCounts. A
     * first-ever full sync (empty table) means every entry dispatches as 'created' — a real, if
     * large, one-time burst for any platform workflow already watching CVE creation.
     */
    private long flushBatch(List<CveEntry> batch) {
        if (batch.isEmpty()) return 0;
        List<String> cveIds = batch.stream().map(CveEntry::getCveId).toList();
        Map<String, CveEntry> existingByCveId = repo.findAllByCveIdIn(cveIds).stream()
            .collect(Collectors.toMap(CveEntry::getCveId, e -> e, (a, b) -> a));

        List<CveEntry> toSave = new ArrayList<>(batch.size());
        List<CveEntry> created = new ArrayList<>();
        List<CveEntry> updated = new ArrayList<>();

        for (CveEntry fresh : batch) {
            CveEntry existing = existingByCveId.get(fresh.getCveId());
            if (existing == null) {
                toSave.add(fresh);
                created.add(fresh);
                continue;
            }
            Instant previousLastModified = existing.getLastModifiedAt();
            copyParsedFieldsOnto(existing, fresh);
            toSave.add(existing);
            if (!Objects.equals(previousLastModified, fresh.getLastModifiedAt())) {
                updated.add(existing);
            }
        }
        repo.saveAll(toSave);

        for (CveEntry e : created) workflowEventDispatcher.onCveCreated(e);
        for (CveEntry e : updated) workflowEventDispatcher.onCveUpdated(e);
        return batch.size();
    }

    /** Copies every field {@link #parseFile} actually populates from a freshly-parsed entry onto
     *  an already-persisted one — deliberately NOT the KEV/exploit-count fields, see {@link
     *  #flushBatch}'s own doc comment for why. */
    private void copyParsedFieldsOnto(CveEntry target, CveEntry source) {
        target.setState(source.getState());
        target.setDescription(source.getDescription());
        target.setCvssScore(source.getCvssScore());
        target.setCvssVector(source.getCvssVector());
        target.setCvssVersion(source.getCvssVersion());
        target.setSeverity(source.getSeverity());
        target.setCwes(source.getCwes());
        target.setAffectedProducts(source.getAffectedProducts());
        target.setReferences(source.getReferences());
        target.setCvssScores(source.getCvssScores());
        target.setSsvc(source.getSsvc());
        target.setPublishedAt(source.getPublishedAt());
        target.setLastModifiedAt(source.getLastModifiedAt());
        target.setSyncedAt(source.getSyncedAt());
    }

    // ── JSON parser (CVE 5.0 format) ──────────────────────────────────────

    private CveEntry parseFile(Path file, Instant syncedAt) throws IOException {
        JsonNode root = objectMapper.readTree(file.toFile());

        JsonNode meta = root.path("cveMetadata");
        String cveId = meta.path("cveId").asText(null);
        if (cveId == null || cveId.isBlank()) return null;

        CveEntry e = new CveEntry();
        e.setCveId(cveId);
        e.setState(meta.path("state").asText("UNKNOWN"));
        e.setPublishedAt(parseInstant(meta.path("datePublished")));
        e.setLastModifiedAt(parseInstant(meta.path("dateUpdated")));
        e.setSyncedAt(syncedAt);

        JsonNode cna = root.path("containers").path("cna");
        JsonNode adp = root.path("containers").path("adp");

        e.setDescription(extractEnglishDescription(cna.path("descriptions")));

        processCvssScores(e, cna, adp);
        processSsvc(e, adp);

        e.setCwes(extractCwes(cna.path("problemTypes")));
        e.setAffectedProducts(extractAffected(cna.path("affected")));
        e.setReferences(extractReferences(cna.path("references")));

        return e;
    }

    private String extractEnglishDescription(JsonNode descriptions) {
        if (!descriptions.isArray()) return null;
        String fallback = null;
        for (JsonNode d : descriptions) {
            String lang = d.path("lang").asText("");
            String value = d.path("value").asText(null);
            if (value == null || value.isBlank()) continue;
            if (lang.startsWith("en")) return value;
            if (fallback == null) fallback = value;
        }
        return fallback;
    }

    // ── CVSS multi-score extraction ───────────────────────────────────────

    private static final String[] CVSS_KEYS = {"cvssV4_0", "cvssV3_1", "cvssV3_0", "cvssV2_0"};
    private static final Map<String, String> CVSS_VERSIONS = Map.of(
        "cvssV4_0", "4.0", "cvssV3_1", "3.1", "cvssV3_0", "3.0", "cvssV2_0", "2.0");

    private void processCvssScores(CveEntry e, JsonNode cna, JsonNode adp) {
        String cnaSource = cna.path("providerMetadata").path("shortName").asText(null);
        List<CvssCandidate> all = new ArrayList<>(collectCandidates(cna.path("metrics"), cnaSource, "CNA"));

        if (adp.isArray()) {
            for (JsonNode provider : adp) {
                String src = provider.path("providerMetadata").path("shortName").asText(null);
                all.addAll(collectCandidates(provider.path("metrics"), src, "ADP"));
            }
        }

        if (all.isEmpty()) return;

        // Choose default: NVD ADP (priority 2) > other ADP (1) > CNA (0); break ties by CVSS version (higher = better).
        int defaultIdx = 0, bestSrc = -1, bestVer = -1;
        for (int i = 0; i < all.size(); i++) {
            CvssCandidate c = all.get(i);
            int sp = sourcePriority(c), vp = versionPriority(c.version());
            if (sp > bestSrc || (sp == bestSrc && vp > bestVer)) {
                defaultIdx = i; bestSrc = sp; bestVer = vp;
            }
        }

        List<CveEntry.CvssScore> scores = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            CvssCandidate c = all.get(i);
            scores.add(new CveEntry.CvssScore(c.source(), c.role(), c.version(), c.score(), c.vector(), c.severity(), i == defaultIdx));
        }
        e.setCvssScores(scores);

        // Denormalise default to top-level fields so queries/indexes keep working.
        CvssCandidate def = all.get(defaultIdx);
        e.setCvssScore(def.score());
        e.setCvssVector(def.vector());
        e.setCvssVersion(def.version());
        e.setSeverity(def.severity());
    }

    // ── SSVC (CISA's own assessment, when published) ───────────────────────

    /** CISA publishes at most one SSVC assessment per CVE, as an ADP metrics entry shaped
     *  {@code {"other": {"type": "ssvc", "content": {"role", "options": [{"Exploitation": ...},
     *  {"Automatable": ...}, {"Technical Impact": ...}], "version", "timestamp"}}}}. Display-
     *  only — see the field doc on CveEntry.ssvc for why no outcome is computed. */
    private void processSsvc(CveEntry e, JsonNode adp) {
        if (!adp.isArray()) return;
        for (JsonNode provider : adp) {
            for (JsonNode metric : provider.path("metrics")) {
                JsonNode other = metric.path("other");
                if (!"ssvc".equals(other.path("type").asText(null))) continue;
                JsonNode content = other.path("content");
                String exploitation = null, automatable = null, technicalImpact = null;
                for (JsonNode opt : content.path("options")) {
                    if (opt.has("Exploitation")) exploitation = opt.path("Exploitation").asText(null);
                    else if (opt.has("Automatable")) automatable = opt.path("Automatable").asText(null);
                    else if (opt.has("Technical Impact")) technicalImpact = opt.path("Technical Impact").asText(null);
                }
                e.setSsvc(new CveEntry.SsvcAssessment(
                    content.path("role").asText(null), exploitation, automatable, technicalImpact,
                    content.path("version").asText(null), parseInstant(content.path("timestamp"))));
                return;
            }
        }
    }

    private List<CvssCandidate> collectCandidates(JsonNode metrics, String source, String role) {
        List<CvssCandidate> result = new ArrayList<>();
        if (!metrics.isArray()) return result;
        for (JsonNode metric : metrics) {
            for (String key : CVSS_KEYS) {
                JsonNode cvss = metric.path(key);
                if (cvss.isMissingNode()) continue;
                JsonNode scoreNode = cvss.path("baseScore");
                if (!scoreNode.isNumber()) continue;
                String vector = firstNonNull(cvss.path("vectorString").asText(null), cvss.path("vector").asText(null));
                String severity = firstNonNull(cvss.path("baseSeverity").asText(null), cvss.path("severity").asText(null));
                result.add(new CvssCandidate(source, role, CVSS_VERSIONS.get(key), scoreNode.asDouble(),
                    vector, severity != null ? severity.toUpperCase() : null));
            }
        }
        return result;
    }

    private int sourcePriority(CvssCandidate c) {
        if ("ADP".equals(c.role()) && isNvd(c.source())) return 2;
        if ("ADP".equals(c.role())) return 1;
        return 0;
    }

    private boolean isNvd(String source) {
        return source != null && (source.toLowerCase().contains("nvd") || source.toLowerCase().contains("nist"));
    }

    private int versionPriority(String version) {
        return switch (version != null ? version : "") {
            case "4.0" -> 4; case "3.1" -> 3; case "3.0" -> 2; case "2.0" -> 1; default -> 0;
        };
    }

    private List<String> extractCwes(JsonNode problemTypes) {
        List<String> cwes = new ArrayList<>();
        if (!problemTypes.isArray()) return cwes;
        for (JsonNode pt : problemTypes) {
            JsonNode descs = pt.path("descriptions");
            if (!descs.isArray()) continue;
            for (JsonNode d : descs) {
                if ("CWE".equalsIgnoreCase(d.path("type").asText(""))) {
                    String cweId = d.path("cweId").asText(null);
                    if (cweId != null && !cweId.isBlank()) cwes.add(cweId);
                }
            }
        }
        return cwes;
    }

    private List<CveEntry.AffectedProduct> extractAffected(JsonNode affected) {
        List<CveEntry.AffectedProduct> products = new ArrayList<>();
        if (!affected.isArray()) return products;
        for (JsonNode a : affected) {
            String vendor = a.path("vendor").asText(null);
            String product = a.path("product").asText(null);
            String defaultStatus = a.path("defaultStatus").asText(null);
            List<CveEntry.VersionRange> versions = new ArrayList<>();
            JsonNode vs = a.path("versions");
            if (vs.isArray()) {
                for (JsonNode v : vs) {
                    String ver = v.path("version").asText(null);
                    String lessThan = v.path("lessThan").asText(null);
                    String lessThanOrEqual = v.path("lessThanOrEqual").asText(null);
                    // A range with neither a usable start value nor either bound carries no
                    // information to show. "n/a" is a CNA placeholder for "no version data" —
                    // skip it too, unless it's paired with a real bound (rare, but "before X"
                    // is still meaningful even with an unknown start).
                    boolean noStart = ver == null || ver.isBlank() || "n/a".equalsIgnoreCase(ver);
                    if (noStart && lessThan == null && lessThanOrEqual == null) continue;
                    String status = v.path("status").asText(null);
                    String versionType = v.path("versionType").asText(null);
                    versions.add(new CveEntry.VersionRange(ver, status, lessThan, lessThanOrEqual, versionType));
                }
            }
            if (vendor != null || product != null)
                products.add(new CveEntry.AffectedProduct(vendor, product, defaultStatus, versions));
        }
        return products;
    }

    private List<CveEntry.Reference> extractReferences(JsonNode references) {
        List<CveEntry.Reference> refs = new ArrayList<>();
        if (!references.isArray()) return refs;
        for (JsonNode r : references) {
            String url = r.path("url").asText(null);
            if (url == null || url.isBlank()) continue;
            String name = r.path("name").asText(null);
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = r.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode t : tagsNode) {
                    if (t.isTextual()) tags.add(t.asText());
                }
            }
            refs.add(new CveEntry.Reference(url, name, tags));
        }
        return refs;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Instant parseInstant(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        try {
            String text = node.asText().trim();
            if (!text.contains("T")) text = text.replace(" ", "T");
            if (!text.endsWith("Z") && !text.contains("+")) text = text + "Z";
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /**
     * Removes stale git lock files left by a previously interrupted git process.
     * Safe to call before any git operation — git creates these files atomically,
     * so deleting a lock when no other git process is running is always correct.
     */
    private void clearGitLocks(Path repoPath) {
        Path gitDir = repoPath.resolve(".git");
        if (!Files.isDirectory(gitDir)) return;
        String[] lockFiles = { "index.lock", "HEAD.lock", "ORIG_HEAD.lock", "MERGE_HEAD.lock",
                               "CHERRY_PICK_HEAD.lock", "REVERT_HEAD.lock", "BISECT_LOG.lock" };
        for (String name : lockFiles) {
            Path lock = gitDir.resolve(name);
            try {
                if (Files.deleteIfExists(lock)) {
                    log.warn("Removed stale git lock file: {}", lock);
                }
            } catch (Exception e) {
                log.warn("Could not remove git lock {}: {}", lock, e.getMessage());
            }
        }
        // Also scan for any remaining *.lock files in refs/
        try (var stream = Files.walk(gitDir, 3)) {
            stream.filter(p -> p.toString().endsWith(".lock"))
                  .forEach(p -> {
                      try {
                          if (Files.deleteIfExists(p)) log.warn("Removed stale git lock: {}", p);
                      } catch (Exception ignored) {}
                  });
        } catch (Exception e) {
            log.warn("Error scanning for git locks: {}", e.getMessage());
        }
    }

    private String git(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String output;
        try (InputStream is = p.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " exited " + exitCode + ": " + output.trim());
        }
        return output;
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }

    // ── DTO ───────────────────────────────────────────────────────────────

    public record SyncStats(long total, long critical, long high, Instant lastSync, String lastCommit) {
        public boolean isSynced() { return total > 0; }
    }
}
