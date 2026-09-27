package com.martecyber.ares.kb.capec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class CapecService {

    private static final Logger log = LoggerFactory.getLogger(CapecService.class);

    private final CapecRepository repo;
    private final CapecXmlParser parser;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final CapecAqlRegistry aqlRegistry;
    private final HttpClient http;

    public CapecService(CapecRepository repo, CapecXmlParser parser, JobService jobService, ObjectMapper objectMapper,
                         CapecAqlRegistry aqlRegistry) {
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

    public Optional<CapecEntry> findById(String capecId) {
        String id = capecId.replaceFirst("(?i)^CAPEC-", "");
        return repo.findByCapecId(id);
    }

    /** {@code rawIds} may arrive lowercase (same class of bug CweService#resolveNames had — see
     *  its own doc comment) — the prefix strip must be case-insensitive or a lowercase id passes
     *  through unstripped and never matches CapecEntry.capecId. */
    public List<NameDto> resolveNames(List<String> rawIds) {
        List<String> bare = rawIds.stream()
            .map(id -> id.replaceFirst("(?i)^CAPEC-", ""))
            .toList();
        return repo.findAllByCapecIdIn(bare).stream()
            .map(e -> new NameDto(e.getCapecId(), e.getName()))
            .toList();
    }

    public record NameDto(String id, String name) {}

    public Page<CapecEntry> findAll(Pageable p) { return repo.findAll(p); }

    public Page<CapecEntry> findByAbstraction(String abstraction, Pageable p) { return repo.findByAbstraction(abstraction, p); }

    public Page<CapecEntry> findBySeverity(String severity, Pageable p) { return repo.findByTypicalSeverity(severity, p); }

    /** Direct CAPEC-as-primary-entity AQL query — Postgres-backed since Phase 5 of the AQL-wide
     *  initiative, and also the resolution target for Detection/Finding's capec.* RelationAqlField. */
    public Page<CapecEntry> findByAql(String aql, Pageable p) {
        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        var spec = new PostgresSpecificationCompiler<>(aqlRegistry).compile(node);
        return repo.findAll(spec, p);
    }

    public Page<CapecEntry> findByCwe(String cweId, Pageable p) {
        String id = cweId.toUpperCase(Locale.ROOT).startsWith("CWE-") ? cweId : "CWE-" + cweId;
        return repo.findAll(relatedCweIdsContains(id), p);
    }

    public Page<CapecEntry> search(String keyword, Pageable p) {
        Specification<CapecEntry> spec = (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("name")), "%" + keyword.toLowerCase() + "%"),
            cb.like(cb.lower(root.get("description")), "%" + keyword.toLowerCase() + "%"));
        return repo.findAll(spec, p);
    }

    public Page<CapecEntry> filter(String q, List<String> abstraction, List<String> severity, List<String> likelihoodOfAttack, String cwe, Pageable pageable) {
        Specification<CapecEntry> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)));
            }
            if (abstraction != null && !abstraction.isEmpty()) predicates.add(root.get("abstraction").in(abstraction));
            if (severity != null && !severity.isEmpty()) predicates.add(root.get("typicalSeverity").in(severity));
            if (likelihoodOfAttack != null && !likelihoodOfAttack.isEmpty()) predicates.add(root.get("likelihoodOfAttack").in(likelihoodOfAttack));
            if (cwe != null && !cwe.isBlank()) {
                String cweId = cwe.toUpperCase(Locale.ROOT).startsWith("CWE-") ? cwe : "CWE-" + cwe;
                predicates.add(relatedCweIdsContains(cweId).toPredicate(root, query, cb));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repo.findAll(spec, pageable);
    }

    /** {@code array_position(related_cwe_ids, <cweId>) IS NOT NULL} — same qualified-function-name
     *  precedent as PostgresSpecificationCompiler.arrayContainsPredicate (calling the bare name
     *  "array_position" collides with Hibernate 6.4+'s own registered function of the same name,
     *  which wraps it in a portability shim that's wrong composed with isNotNull — a real bug this
     *  session's Phase 3 verification caught). relatedCweIds is lowercase-normalized at write time
     *  (CapecEntry.setRelatedCweIds), so the literal is lowered here too. */
    private Specification<CapecEntry> relatedCweIdsContains(String cweId) {
        return (root, query, cb) -> {
            Expression<Integer> position = cb.function("pg_catalog.array_position", Integer.class,
                root.get("relatedCweIds"), cb.literal(cweId.toLowerCase(Locale.ROOT)));
            return cb.isNotNull(position);
        };
    }

    public SyncStats stats() {
        long total = repo.count();
        Optional<CapecEntry> latest = repo.findTopByOrderBySyncedAtDesc();
        Instant lastSync = latest.map(CapecEntry::getSyncedAt).orElse(null);
        String version = latest.map(CapecEntry::getSourceVersion).orElse(null);
        return new SyncStats(total, lastSync, version);
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_capec", null, null, null));
        syncAsync(job.id());
        return job.id();
    }

    @Async
    public void syncAsync(Long jobId) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting CAPEC sync, job={}", jobId);
            setStatus(jobId, null, 5);

            HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(CapecXmlParser.CAPEC_XML_URL))
                    .timeout(Duration.ofMinutes(10))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200)
                throw new RuntimeException("HTTP " + response.statusCode() + " downloading CAPEC XML");

            setStatus(jobId, null, 15);

            List<CapecEntry> entries = parser.parse(response.body(), count ->
                setStatus(jobId, null, 15 + (int)(count * 60.0 / 700)));

            setStatus(jobId, null, 75);

            for (int i = 0; i < entries.size(); i++) {
                CapecEntry parsed = entries.get(i);
                // Fetch-existing-then-copy-onto, not a detached save with a forced id — the
                // established bulk-upsert-by-natural-key pattern from CveService.flushBatch.
                CapecEntry toSave = repo.findByCapecId(parsed.getCapecId())
                    .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
                repo.save(toSave);
                if (i % 100 == 0) setStatus(jobId, null, 75 + (int)(i * 20.0 / entries.size()));
            }

            String resultJson = objectMapper.writeValueAsString(Map.of("saved", entries.size()));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, resultJson, null));
            log.info("CAPEC sync completed job={} entries={}", jobId, entries.size());

        } catch (Exception ex) {
            log.error("CAPEC sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    /** Copies every parsed field from {@code parsed} onto the already-managed {@code existing}
     *  row, preserving its id — mirrors CveService.copyParsedFieldsOnto's established shape. */
    private CapecEntry copyOnto(CapecEntry existing, CapecEntry parsed) {
        existing.setName(parsed.getName());
        existing.setAbstraction(parsed.getAbstraction());
        existing.setStatus(parsed.getStatus());
        existing.setDescription(parsed.getDescription());
        existing.setExtendedDescription(parsed.getExtendedDescription());
        existing.setTypicalSeverity(parsed.getTypicalSeverity());
        existing.setLikelihoodOfAttack(parsed.getLikelihoodOfAttack());
        existing.setPrerequisites(parsed.getPrerequisites());
        existing.setMitigations(parsed.getMitigations());
        existing.setConsequences(parsed.getConsequences());
        existing.setRelatedCweIds(parsed.getRelatedCweIds());
        existing.setRelatedAttackTechniqueIds(parsed.getRelatedAttackTechniqueIds());
        existing.setParentCapecIds(parsed.getParentCapecIds());
        existing.setChildCapecIds(parsed.getChildCapecIds());
        existing.setDomains(parsed.getDomains());
        existing.setExecutionFlow(parsed.getExecutionFlow());
        existing.setRelatedAttackPatterns(parsed.getRelatedAttackPatterns());
        existing.setReferences(parsed.getReferences());
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
