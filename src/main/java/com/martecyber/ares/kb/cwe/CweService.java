package com.martecyber.ares.kb.cwe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

@Service
public class CweService {

    private static final Logger log = LoggerFactory.getLogger(CweService.class);

    private final CweRepository repo;
    private final CweXmlParser parser;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final CweAqlRegistry aqlRegistry;
    private final HttpClient http;

    public CweService(CweRepository repo, CweXmlParser parser, JobService jobService, ObjectMapper objectMapper,
                       CweAqlRegistry aqlRegistry) {
        this.repo = repo;
        this.parser = parser;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.aqlRegistry = aqlRegistry;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public Optional<CweEntry> findById(String cweId) {
        String id = cweId.replaceFirst("(?i)^CWE-", "");
        return repo.findByCweId(id);
    }

    /** {@code rawIds} may arrive lowercase (e.g. CveEntry.cwes, stored "cwe-770" — CveEntry
     *  lowercases at write time) — the prefix strip must be case-insensitive or a lowercase id
     *  passes through unstripped, never matches CweEntry.cweId (bare, e.g. "770"), and silently
     *  resolves to no name at all. */
    public List<NameDto> resolveNames(List<String> rawIds) {
        List<String> bare = rawIds.stream()
            .map(id -> id.replaceFirst("(?i)^CWE-", ""))
            .toList();
        return repo.findAllByCweIdIn(bare).stream()
            .map(e -> new NameDto(e.getCweId(), e.getName()))
            .toList();
    }

    public record NameDto(String id, String name) {}

    public Page<CweEntry> findAll(Pageable p) { return repo.findAll(p); }

    public Page<CweEntry> findByType(String type, Pageable p) { return repo.findByType(type, p); }

    public Page<CweEntry> findByAbstraction(String abstraction, Pageable p) { return repo.findByAbstraction(abstraction, p); }

    public Page<CweEntry> search(String keyword, Pageable p) {
        Specification<CweEntry> spec = (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("name")), "%" + keyword.toLowerCase() + "%"),
            cb.like(cb.lower(root.get("description")), "%" + keyword.toLowerCase() + "%"));
        return repo.findAll(spec, p);
    }

    /** Direct CWE-as-primary-entity AQL query — Postgres-backed since Phase 5 of the AQL-wide
     *  initiative, and also the resolution target for Detection/Finding's cwe.* RelationAqlField. */
    public Page<CweEntry> findByAql(String aql, Pageable p) {
        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        var spec = new PostgresSpecificationCompiler<>(aqlRegistry).compile(node);
        return repo.findAll(spec, p);
    }

    public Page<CweEntry> filter(String q, List<String> type, List<String> abstraction, List<String> likelihoodOfExploit, Pageable pageable) {
        Specification<CweEntry> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)));
            }
            if (type != null && !type.isEmpty()) predicates.add(root.get("type").in(type));
            if (abstraction != null && !abstraction.isEmpty()) predicates.add(root.get("abstraction").in(abstraction));
            if (likelihoodOfExploit != null && !likelihoodOfExploit.isEmpty())
                predicates.add(root.get("likelihoodOfExploit").in(likelihoodOfExploit));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return repo.findAll(spec, pageable);
    }

    public SyncStats stats() {
        long total = repo.count();
        Optional<CweEntry> latest = repo.findTopByOrderBySyncedAtDesc();
        Instant lastSync = latest.map(CweEntry::getSyncedAt).orElse(null);
        String version = latest.map(CweEntry::getSourceVersion).orElse(null);
        return new SyncStats(total, lastSync, version);
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_cwe", null, null, null));
        syncAsync(job.id());
        return job.id();
    }

    @Async
    public void syncAsync(Long jobId) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting CWE sync, job={}", jobId);
            setStatus(jobId, null, 5);

            HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(CweXmlParser.CWE_ZIP_URL))
                    .timeout(Duration.ofMinutes(10))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200)
                throw new RuntimeException("HTTP " + response.statusCode() + " downloading CWE ZIP");

            setStatus(jobId, null, 15);

            CweXmlParser.ParseResult result;
            try (ZipInputStream zip = new ZipInputStream(response.body())) {
                zip.getNextEntry();
                result = parser.parse(zip, count -> setStatus(jobId, null, 15 + Math.min((int)(count * 55.0 / 1500), 55)));
            }

            setStatus(jobId, null, 70);

            List<CweEntry> entries = result.entries();
            for (int i = 0; i < entries.size(); i++) {
                CweEntry parsed = entries.get(i);
                // Fetch-existing-then-copy-onto, not a detached save with a forced id — the
                // established bulk-upsert-by-natural-key pattern from CveService.flushBatch.
                CweEntry toSave = repo.findByCweId(parsed.getCweId())
                    .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
                repo.save(toSave);
                if (i % 300 == 0) setStatus(jobId, null, 70 + (int)(i * 25.0 / entries.size()));
            }

            String resultJson = objectMapper.writeValueAsString(Map.of(
                "saved", entries.size(),
                "version", result.version() != null ? result.version() : "unknown"
            ));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, resultJson, null));
            log.info("CWE sync completed job={} entries={}", jobId, entries.size());

        } catch (Exception ex) {
            log.error("CWE sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    /** Copies every parsed field from {@code parsed} onto the already-managed {@code existing}
     *  row, preserving its id — mirrors CveService.copyParsedFieldsOnto's established shape. */
    private CweEntry copyOnto(CweEntry existing, CweEntry parsed) {
        existing.setCode(parsed.getCode());
        existing.setName(parsed.getName());
        existing.setType(parsed.getType());
        existing.setAbstraction(parsed.getAbstraction());
        existing.setStatus(parsed.getStatus());
        existing.setDescription(parsed.getDescription());
        existing.setExtendedDescription(parsed.getExtendedDescription());
        existing.setConsequences(parsed.getConsequences());
        existing.setMitigations(parsed.getMitigations());
        existing.setParentIds(parsed.getParentIds());
        existing.setChildIds(parsed.getChildIds());
        existing.setRelatedCapecIds(parsed.getRelatedCapecIds());
        existing.setLikelihoodOfExploit(parsed.getLikelihoodOfExploit());
        existing.setApplicablePlatforms(parsed.getApplicablePlatforms());
        existing.setObservedExamples(parsed.getObservedExamples());
        existing.setVulnerabilityMapping(parsed.getVulnerabilityMapping());
        existing.setRelatedWeaknesses(parsed.getRelatedWeaknesses());
        existing.setReferences(parsed.getReferences());
        existing.setMemberships(parsed.getMemberships());
        existing.setNotes(parsed.getNotes());
        existing.setAlternateTerms(parsed.getAlternateTerms());
        existing.setDetectionMethods(parsed.getDetectionMethods());
        existing.setSyncedAt(parsed.getSyncedAt());
        existing.setSourceVersion(parsed.getSourceVersion());
        return existing;
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    public record SyncStats(long total, Instant lastSync, String version) {
        public boolean isSynced() { return total > 0; }
    }
}
