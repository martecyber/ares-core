package com.martecyber.ares.kb.attack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AttackService {

    private static final Logger log = LoggerFactory.getLogger(AttackService.class);

    private static final List<String> MATRICES = List.of("enterprise-attack", "mobile-attack", "ics-attack");
    private static final Map<String, String> MATRIX_URLS = Map.of(
        "enterprise-attack", AttackStixParser.ENTERPRISE_URL,
        "mobile-attack",     AttackStixParser.MOBILE_URL,
        "ics-attack",        AttackStixParser.ICS_URL
    );

    private final AttackTacticRepository tacticRepo;
    private final AttackTechniqueRepository techniqueRepo;
    private final AttackMitigationRepository mitigationRepo;
    private final AttackTechniqueTacticRepository techniqueTacticRepo;
    private final AttackTechniqueMitigationRepository techniqueMitigationRepo;
    private final AttackStixParser parser;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final AttackAqlRegistry aqlRegistry;
    private final HttpClient http;

    public AttackService(
        AttackTacticRepository tacticRepo,
        AttackTechniqueRepository techniqueRepo,
        AttackMitigationRepository mitigationRepo,
        AttackTechniqueTacticRepository techniqueTacticRepo,
        AttackTechniqueMitigationRepository techniqueMitigationRepo,
        AttackStixParser parser,
        JobService jobService,
        ObjectMapper objectMapper,
        AttackAqlRegistry aqlRegistry
    ) {
        this.tacticRepo = tacticRepo;
        this.techniqueRepo = techniqueRepo;
        this.mitigationRepo = mitigationRepo;
        this.techniqueTacticRepo = techniqueTacticRepo;
        this.techniqueMitigationRepo = techniqueMitigationRepo;
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

    public List<AttackTactic> tacticsByMatrix(String matrix) {
        return tacticRepo.findByMatrixOrderByOrderAsc(matrix);
    }

    public Page<AttackTechnique> techniques(String matrix, String tactic, boolean subtechniquesOnly, Pageable p) {
        if (tactic != null && !tactic.isBlank()) return techniqueRepo.findByMatrixAndTacticsContaining(matrix, tactic, p);
        if (subtechniquesOnly) return techniqueRepo.findByMatrixAndSubtechnique(matrix, true, p);
        return techniqueRepo.findByMatrix(matrix, p);
    }

    /** Direct ATT&CK-technique-as-primary-entity AQL query — Postgres-backed since Phase 5 of the
     *  AQL-wide initiative, and also the resolution target for Detection/Finding's attack.*
     *  RelationAqlField (AttackAqlRegistry covers AttackTechnique only, same scope as before). */
    public Page<AttackTechnique> findByAql(String aql, Pageable p) {
        var node = com.martecyber.ares.aql.parser.AqlParser.parse(aql);
        var spec = new PostgresSpecificationCompiler<>(aqlRegistry).compile(node);
        return techniqueRepo.findAll(spec, p);
    }

    public Page<AttackTechnique> searchTechniques(String matrix, String keyword, Pageable p) {
        return techniqueRepo.search(matrix, keyword, p);
    }

    public Optional<AttackTechnique> findTechnique(String attackId, String matrix) {
        return techniqueRepo.findByAttackIdAndMatrix(attackId, matrix);
    }

    public List<AttackTechnique> allTechniquesForMatrix(String matrix) {
        return techniqueRepo.findAllByMatrixOrderByAttackIdAsc(matrix);
    }

    public List<NameDto> resolveTechniqueNames(List<String> ids) {
        return techniqueRepo.findAllByAttackIdIn(ids).stream()
            .map(t -> new NameDto(t.getAttackId(), t.getName()))
            .toList();
    }

    public record NameDto(String id, String name) {}

    /** Every mitigation the (previously write-only) {@code attack_technique_mitigation} bridge
     *  table links to this technique — built from STIX "mitigates" relationships at sync time,
     *  exposed here for the first time (Phase 5 redesign). */
    public List<AttackMitigation> mitigationsForTechnique(Long techniqueId) {
        List<Long> mitigationIds = techniqueMitigationRepo.findMitigationIdsByTechniqueId(techniqueId);
        if (mitigationIds.isEmpty()) return List.of();
        return mitigationRepo.findAllById(mitigationIds);
    }

    /** Sub-techniques of a (non-sub) technique — derived from the "T1055.001" attackId naming
     *  convention itself (the same convention {@link AttackStixParser#parseTechnique} already
     *  uses to set {@code subtechnique}), not a separate STIX relationship — MITRE's own
     *  "subtechnique-of" relationship objects would require additional parsing this doesn't need. */
    public List<AttackTechnique> subtechniquesOf(String attackId, String matrix) {
        return techniqueRepo.findByMatrixAndAttackIdStartingWith(matrix, attackId + ".");
    }

    public List<AttackMitigation> allMitigationsForMatrix(String matrix) {
        return mitigationRepo.findAllByMatrixOrderByAttackIdAsc(matrix);
    }

    public Page<AttackMitigation> mitigations(String matrix, Pageable p) {
        return mitigationRepo.findByMatrix(matrix, p);
    }

    public Page<AttackMitigation> searchMitigations(String matrix, String keyword, Pageable p) {
        return mitigationRepo.search(matrix, keyword, p);
    }

    public SyncStats stats() {
        long tactics = tacticRepo.count();
        long techniques = techniqueRepo.count();
        long mitigations = mitigationRepo.count();
        Instant lastSync = techniqueRepo.findAll(Pageable.ofSize(1)).stream()
            .map(AttackTechnique::getSyncedAt).findFirst().orElse(null);
        return new SyncStats(tactics, techniques, mitigations, lastSync);
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    public Long triggerSync() {
        var job = jobService.create(new CreateJobRequest("kb_sync_attack", null, null, null));
        syncAsync(job.id());
        return job.id();
    }

    @Async
    public void syncAsync(Long jobId) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting ATT&CK sync, job={}", jobId);
            int totalTactics = 0, totalTechniques = 0, totalMitigations = 0, totalMitigatesRelationships = 0;

            for (int mi = 0; mi < MATRICES.size(); mi++) {
                String matrix = MATRICES.get(mi);
                int baseProgress = mi * 30;
                setStatus(jobId, null, baseProgress + 2);
                log.info("Syncing matrix: {}", matrix);

                HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(MATRIX_URLS.get(matrix)))
                        .timeout(Duration.ofMinutes(10))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200)
                    throw new RuntimeException("HTTP " + response.statusCode() + " for " + matrix);

                setStatus(jobId, null, baseProgress + 5);

                AttackStixParser.ParseResult result = parser.parse(response.body(), matrix,
                    pct -> setStatus(jobId, null, baseProgress + 5 + pct / 5));

                setStatus(jobId, null, baseProgress + 25);
                saveMatrix(result);
                setStatus(jobId, null, baseProgress + 30);

                totalTactics += result.tactics().size();
                totalTechniques += result.techniques().size();
                totalMitigations += result.mitigations().size();
                totalMitigatesRelationships += result.mitigatesRelationships().size();
            }

            String resultJson = objectMapper.writeValueAsString(Map.of(
                "tactics", totalTactics,
                "techniques", totalTechniques,
                "mitigations", totalMitigations,
                "mitigatesRelationships", totalMitigatesRelationships
            ));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, resultJson, null));
            log.info("ATT&CK sync completed job={}", jobId);

        } catch (Exception ex) {
            log.error("ATT&CK sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    private void saveMatrix(AttackStixParser.ParseResult result) {
        String matrix = result.matrix();

        // Tactics first — techniques' tactic-short-name resolution (below) needs every tactic's
        // real id already assigned.
        Map<String, Long> tacticIdByShortName = new HashMap<>();
        for (AttackTactic parsed : result.tactics()) {
            AttackTactic toSave = tacticRepo.findByAttackIdAndMatrix(parsed.getAttackId(), matrix)
                .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
            AttackTactic saved = tacticRepo.save(toSave);
            if (saved.getShortName() != null) tacticIdByShortName.put(saved.getShortName(), saved.getId());
        }

        Map<String, Long> techniqueIdByStixId = new HashMap<>();
        for (AttackTechnique parsed : result.techniques()) {
            if (parsed.getAttackId() == null) continue;
            AttackTechnique toSave = techniqueRepo.findByAttackIdAndMatrix(parsed.getAttackId(), matrix)
                .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
            AttackTechnique saved = techniqueRepo.save(toSave);
            if (saved.getStixId() != null) techniqueIdByStixId.put(saved.getStixId(), saved.getId());
        }

        Map<String, Long> mitigationIdByStixId = new HashMap<>();
        for (AttackMitigation parsed : result.mitigations()) {
            if (parsed.getAttackId() == null) continue;
            AttackMitigation toSave = mitigationRepo.findByAttackIdAndMatrix(parsed.getAttackId(), matrix)
                .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
            AttackMitigation saved = mitigationRepo.save(toSave);
            if (saved.getStixId() != null) mitigationIdByStixId.put(saved.getStixId(), saved.getId());
        }

        // Rebuild both bridge tables fresh from this parse, scoped to this matrix — simpler and
        // just as correct as a diff/reconcile, since a full matrix sync always re-parses every
        // technique/mitigation/relationship in that matrix's bundle anyway.
        List<AttackTechniqueTactic> techniqueTacticRows = new ArrayList<>();
        for (AttackTechnique parsed : result.techniques()) {
            if (parsed.getStixId() == null) continue;
            Long techniqueId = techniqueIdByStixId.get(parsed.getStixId());
            if (techniqueId == null) continue;
            for (String shortName : parsed.getTactics()) {
                Long tacticId = tacticIdByShortName.get(shortName);
                if (tacticId != null) techniqueTacticRows.add(new AttackTechniqueTactic(techniqueId, tacticId));
            }
        }
        techniqueTacticRepo.deleteByMatrix(matrix);
        techniqueTacticRepo.saveAll(techniqueTacticRows);

        List<AttackTechniqueMitigation> techniqueMitigationRows = new ArrayList<>();
        for (AttackStixParser.MitigatesRelationship rel : result.mitigatesRelationships()) {
            Long techniqueId = techniqueIdByStixId.get(rel.techniqueStixId());
            Long mitigationId = mitigationIdByStixId.get(rel.mitigationStixId());
            if (techniqueId != null && mitigationId != null)
                techniqueMitigationRows.add(new AttackTechniqueMitigation(techniqueId, mitigationId));
        }
        techniqueMitigationRepo.deleteByMatrix(matrix);
        techniqueMitigationRepo.saveAll(techniqueMitigationRows);
    }

    private AttackTactic copyOnto(AttackTactic existing, AttackTactic parsed) {
        existing.setStixId(parsed.getStixId());
        existing.setName(parsed.getName());
        existing.setDescription(parsed.getDescription());
        existing.setShortName(parsed.getShortName());
        existing.setOrder(parsed.getOrder());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    private AttackTechnique copyOnto(AttackTechnique existing, AttackTechnique parsed) {
        existing.setStixId(parsed.getStixId());
        existing.setName(parsed.getName());
        existing.setDescription(parsed.getDescription());
        existing.setSubtechnique(parsed.isSubtechnique());
        existing.setTactics(parsed.getTactics());
        existing.setPlatforms(parsed.getPlatforms());
        existing.setDataSources(parsed.getDataSources());
        existing.setDetection(parsed.getDetection());
        existing.setPermissionsRequired(parsed.getPermissionsRequired());
        existing.setReferences(parsed.getReferences());
        existing.setDeprecated(parsed.isDeprecated());
        existing.setRevoked(parsed.isRevoked());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    private AttackMitigation copyOnto(AttackMitigation existing, AttackMitigation parsed) {
        existing.setStixId(parsed.getStixId());
        existing.setName(parsed.getName());
        existing.setDescription(parsed.getDescription());
        existing.setDeprecated(parsed.isDeprecated());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    public record SyncStats(long tactics, long techniques, long mitigations, Instant lastSync) {
        public boolean isSynced() { return techniques > 0; }
    }
}
