package com.martecyber.ares.kb.kev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.jobs.JobCancelledException;
import com.martecyber.ares.jobs.JobService;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import com.martecyber.ares.kb.cve.CveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the actual {@code @Async} sync work, in a bean separate from {@link CisaKevService}.
 * Spring's {@code @Async} proxy is bypassed on self-invocation (calling {@code this.syncAsync(...)}
 * from within the same class runs synchronously on the caller's thread) — that silently made
 * "trigger sync" HTTP calls block until the whole sync finished, which timed out client-side on
 * anything but the smallest catalogs. Calling through this separate, injected bean goes through
 * the real proxy, so {@code @Async} actually takes effect.
 */
@Service
public class CisaKevSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(CisaKevSyncRunner.class);
    static final String SOURCE = "cisa";

    private final CveKevDetailRepository repo;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final CveService cveService;
    private final HttpClient http;

    public CisaKevSyncRunner(CveKevDetailRepository repo, JobService jobService, ObjectMapper objectMapper, CveService cveService) {
        this.repo = repo;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.cveService = cveService;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Async
    public void syncAsync(Long jobId, boolean wipeFirst) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting CISA KEV sync, job={} wipeFirst={}", jobId, wipeFirst);
            setStatus(jobId, null, 10);

            if (jobService.isCancelled(jobId)) throw new JobCancelledException(jobId);

            if (wipeFirst) {
                // Only this source's rows — both KEV sources now share ares.cve_kev_detail.
                repo.deleteBySource(SOURCE);
                setStatus(jobId, null, 20);
            }

            HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(CisaKevService.KEV_FEED_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                throw new RuntimeException("HTTP " + response.statusCode() + " from CISA KEV feed");

            setStatus(jobId, null, 40);

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode vulns = root.path("vulnerabilities");
            Instant now = Instant.now();

            List<CveKevDetail> entries = parseEntries(vulns, now);
            int saved = 0;
            for (CveKevDetail parsed : entries) {
                if (jobService.isCancelled(jobId)) throw new JobCancelledException(jobId);
                // Fetch-existing-then-copy-onto, not a detached save with a forced id — the
                // established bulk-upsert-by-natural-key pattern from CveService.flushBatch
                // (Phase 3), which also sidesteps needing a settable id on the entity.
                CveKevDetail toSave = wipeFirst ? parsed
                    : repo.findByCveIdAndSource(parsed.getCveId(), SOURCE)
                        .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
                repo.save(toSave);
                saved++;
                if (saved % 200 == 0) {
                    int progress = 40 + (int) (saved * 55.0 / Math.max(entries.size(), 1));
                    setStatus(jobId, null, Math.min(progress, 95));
                }
            }

            setStatus(jobId, null, 96);
            Map<String, LocalDate> currentKev = new HashMap<>();
            for (CveKevDetail e : entries) currentKev.put(e.getCveId(), e.getDateAdded());
            cveService.updateKevFlags(currentKev);

            String resultJson = objectMapper.writeValueAsString(Map.of("saved", saved));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, resultJson, null));
            log.info("CISA KEV sync completed job={} saved={}", jobId, saved);

        } catch (JobCancelledException jce) {
            log.info("CISA KEV sync job={} was cancelled — stopping", jobId);
        } catch (Exception ex) {
            log.error("CISA KEV sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    private List<CveKevDetail> parseEntries(JsonNode vulns, Instant syncedAt) {
        List<CveKevDetail> result = new ArrayList<>();
        if (!vulns.isArray()) return result;
        for (JsonNode node : vulns) {
            try {
                CveKevDetail e = new CveKevDetail();
                String cveId = node.path("cveID").asText(null);
                if (cveId == null || cveId.isBlank()) continue;
                e.setCveId(cveId.toUpperCase());
                e.setSource(SOURCE);

                e.setVendorProject(node.path("vendorProject").asText(null));
                e.setProduct(node.path("product").asText(null));
                e.setVulnerabilityName(node.path("vulnerabilityName").asText(null));
                e.setShortDescription(node.path("shortDescription").asText(null));
                e.setRequiredAction(node.path("requiredAction").asText(null));
                e.setNotes(node.path("notes").asText(null));
                e.setDateAdded(parseDate(node.path("dateAdded")));
                e.setDueDate(parseDate(node.path("dueDate")));
                e.setKnownRansomwareCampaignUse("Known".equalsIgnoreCase(node.path("knownRansomwareCampaignUse").asText(null)));
                e.setSyncedAt(syncedAt);

                List<String> cwes = new ArrayList<>();
                JsonNode cweNode = node.path("cwes");
                if (cweNode.isArray()) {
                    for (JsonNode c : cweNode) {
                        String cwe = c.asText(null);
                        if (cwe != null && !cwe.isBlank()) cwes.add(cwe);
                    }
                }
                e.setCwes(cwes);

                result.add(e);
            } catch (Exception ex) {
                log.warn("Failed to parse CISA KEV entry: {}", ex.getMessage());
            }
        }
        return result;
    }

    /** Copies every parsed (source-owned) field from {@code parsed} onto the already-managed
     *  {@code existing} row, preserving its id — mirrors CveService.copyParsedFieldsOnto's
     *  established shape from the Phase 3 CVE migration. */
    private CveKevDetail copyOnto(CveKevDetail existing, CveKevDetail parsed) {
        existing.setVendorProject(parsed.getVendorProject());
        existing.setProduct(parsed.getProduct());
        existing.setVulnerabilityName(parsed.getVulnerabilityName());
        existing.setShortDescription(parsed.getShortDescription());
        existing.setRequiredAction(parsed.getRequiredAction());
        existing.setNotes(parsed.getNotes());
        existing.setDateAdded(parsed.getDateAdded());
        existing.setDueDate(parsed.getDueDate());
        existing.setKnownRansomwareCampaignUse(parsed.isKnownRansomwareCampaignUse());
        existing.setCwes(parsed.getCwes());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    private LocalDate parseDate(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        try { return LocalDate.parse(node.asText()); }
        catch (Exception e) { return null; }
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }
}
