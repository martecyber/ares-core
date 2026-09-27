package com.martecyber.ares.kb.owasp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OwaspService {

    private static final Logger log = LoggerFactory.getLogger(OwaspService.class);

    private final OwaspRepository repo;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final OwaspAqlRegistry aqlRegistry;

    public OwaspService(OwaspRepository repo, JobService jobService, ObjectMapper objectMapper,
                         OwaspAqlRegistry aqlRegistry) {
        this.repo = repo;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.aqlRegistry = aqlRegistry;
    }

    private static final int[] EDITIONS = { 2004, 2007, 2010, 2013, 2017, 2021, 2025 };

    // ── Query ─────────────────────────────────────────────────────────────

    public List<OwaspEntry> findAll(Integer year) {
        if (year != null) return repo.findByYearOrderByRankAsc(year);
        return repo.findAllByOrderByYearDescRankAsc();
    }

    public List<OwaspEntry> search(String q, Integer year) {
        if (year != null) {
            return repo.searchByYear(q, year, org.springframework.data.domain.Sort.by("rank"));
        }
        return repo.search(q, org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.desc("year"),
            org.springframework.data.domain.Sort.Order.asc("rank")
        ));
    }

    public Optional<OwaspEntry> findById(String owaspId, Integer year) {
        int y = (year != null) ? year : 2021;
        return repo.findByOwaspIdAndYear(owaspId.toUpperCase(), y);
    }

    public List<Integer> availableYears() { return repo.findDistinctYears(); }

    /** Direct OWASP-as-primary-entity AQL query — Postgres-backed since Phase 5 of the AQL-wide
     *  initiative, unpaginated to match this entity's existing List-returning convention (OWASP's
     *  whole corpus across all editions is a few hundred rows at most). */
    public List<OwaspEntry> findByAql(String aql) {
        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        var spec = new PostgresSpecificationCompiler<>(aqlRegistry).compile(node);
        return repo.findAll(spec);
    }

    public SyncStats stats() {
        long total = repo.count();
        List<Integer> years = repo.findDistinctYears();
        Instant lastSync = repo.findTopByOrderBySyncedAtDesc().map(OwaspEntry::getSyncedAt).orElse(null);
        return new SyncStats(total, years, lastSync);
    }

    // ── Seed ──────────────────────────────────────────────────────────────

    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_owasp", null, null, null));
        seedAsync(job.id());
        return job.id();
    }

    @Async
    public void seedAsync(Long jobId) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting OWASP seed (all editions), job={}", jobId);
            int totalSeeded = 0;
            Instant now = Instant.now();

            for (int idx = 0; idx < EDITIONS.length; idx++) {
                int edition = EDITIONS[idx];
                String path = "kb/owasp-top10-" + edition + ".json";
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    log.warn("OWASP resource not found: {}", path);
                    continue;
                }
                List<Map<String, Object>> raw;
                try (InputStream in = resource.getInputStream()) {
                    raw = objectMapper.readValue(in, new TypeReference<>() {});
                }
                for (Map<String, Object> item : raw) {
                    OwaspEntry parsed = mapEntry(item, now);
                    // Fetch-existing-then-copy-onto, not a detached save with a forced id — the
                    // established bulk-upsert-by-natural-key pattern from CveService.flushBatch.
                    OwaspEntry toSave = repo.findByOwaspIdAndYear(parsed.getOwaspId(), parsed.getYear())
                        .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
                    repo.save(toSave);
                }
                totalSeeded += raw.size();
                log.info("OWASP {} seeded {} entries", edition, raw.size());
                setStatus(jobId, null, 10 + (int)((idx + 1) * 85.0 / EDITIONS.length));
            }

            String result = objectMapper.writeValueAsString(Map.of("seeded", totalSeeded, "editions", EDITIONS.length));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, result, null));
            log.info("OWASP seed completed job={} total={}", jobId, totalSeeded);

        } catch (Exception ex) {
            log.error("OWASP seed failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    /** Copies every parsed field from {@code parsed} onto the already-managed {@code existing}
     *  row, preserving its id — mirrors CveService.copyParsedFieldsOnto's established shape. */
    private OwaspEntry copyOnto(OwaspEntry existing, OwaspEntry parsed) {
        existing.setName(parsed.getName());
        existing.setDescription(parsed.getDescription());
        existing.setRank(parsed.getRank());
        existing.setCwes(parsed.getCwes());
        existing.setPreventions(parsed.getPreventions());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    @SuppressWarnings("unchecked")
    private OwaspEntry mapEntry(Map<String, Object> m, Instant syncedAt) {
        OwaspEntry e = new OwaspEntry();
        e.setOwaspId((String) m.get("owaspId"));
        e.setYear(((Number) m.getOrDefault("year", 2021)).intValue());
        e.setRank(((Number) m.getOrDefault("rank", 0)).intValue());
        e.setName((String) m.get("name"));
        e.setDescription((String) m.get("description"));
        Object cwes = m.get("cwes");
        if (cwes instanceof List<?>) e.setCwes((List<String>) cwes);
        Object prev = m.get("preventions");
        if (prev instanceof List<?>) e.setPreventions((List<String>) prev);
        e.setSyncedAt(syncedAt);
        return e;
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    public record SyncStats(long total, List<Integer> years, Instant lastSync) {
        public boolean isSynced() { return total > 0; }
    }
}
