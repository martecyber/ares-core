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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Holds the actual {@code @Async} sync work, in a bean separate from {@link VulnCheckKevService}.
 * Spring's {@code @Async} proxy is bypassed on self-invocation (calling {@code this.syncAsync(...)}
 * from within the same class runs synchronously on the caller's thread) — that silently made
 * "trigger sync" HTTP calls block until the whole sync finished (downloading + unzipping +
 * parsing the full catalog, which for VulnCheck's larger extended feed easily exceeds a client's
 * request timeout). Calling through this separate, injected bean goes through the real proxy,
 * so {@code @Async} actually takes effect.
 */
@Service
public class VulnCheckKevSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(VulnCheckKevSyncRunner.class);
    static final String SOURCE = "vulncheck";

    private final CveKevDetailRepository repo;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final CveService cveService;
    private final HttpClient http;

    public VulnCheckKevSyncRunner(CveKevDetailRepository repo, JobService jobService,
                                   ObjectMapper objectMapper, CveService cveService) {
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
    public void syncAsync(Long jobId, boolean wipeFirst, String apiKey) {
        setStatus(jobId, "running", 0);
        try {
            log.info("Starting VulnCheck KEV sync, job={} wipeFirst={}", jobId, wipeFirst);
            setStatus(jobId, null, 5);
            if (jobService.isCancelled(jobId)) throw new JobCancelledException(jobId);

            String presignedUrl = fetchBackupUrl(apiKey);
            setStatus(jobId, null, 20);

            byte[] json = downloadAndExtractJson(presignedUrl);
            setStatus(jobId, null, 45);

            if (wipeFirst) {
                // Only this source's rows — both KEV sources now share ares.cve_kev_detail.
                repo.deleteBySource(SOURCE);
                setStatus(jobId, null, 50);
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode vulns = root.path("vulnerabilities");
            if (!vulns.isArray()) vulns = root.isArray() ? root : objectMapper.createArrayNode();
            Instant now = Instant.now();

            // One feed entry can list several CVEs (ParsedEntry.cve) — fanned out to one
            // CveKevDetail row per CVE, matching V151's unified (cve_id, source) shape (the plan's
            // own explicit decision: "one VulnCheck sync record covering 3 CVEs becomes 3 rows").
            List<CveKevDetail> entries = parseEntries(vulns, now).stream()
                .flatMap(this::expand)
                .toList();
            int saved = 0;
            for (CveKevDetail parsed : entries) {
                if (jobService.isCancelled(jobId)) throw new JobCancelledException(jobId);
                CveKevDetail toSave = wipeFirst ? parsed
                    : repo.findByCveIdAndSource(parsed.getCveId(), SOURCE)
                        .map(existing -> copyOnto(existing, parsed)).orElse(parsed);
                repo.save(toSave);
                saved++;
                if (saved % 200 == 0) {
                    int progress = 50 + (int) (saved * 45.0 / Math.max(entries.size(), 1));
                    setStatus(jobId, null, Math.min(progress, 95));
                }
            }

            setStatus(jobId, null, 96);
            // Falls back to cisaDateAdded when VulnCheck's own dateAdded is absent — matches the
            // original Mongo-era logic exactly; the entity's OWN dateAdded/cisaDateAdded columns
            // stay separate and unfallback-ed (this fallback is only for the CVE-flags snapshot).
            Map<String, LocalDate> currentKev = new HashMap<>();
            for (CveKevDetail e : entries) {
                LocalDate dateAdded = e.getDateAdded() != null ? e.getDateAdded() : e.getCisaDateAdded();
                currentKev.put(e.getCveId(), dateAdded);
            }
            cveService.updateVulnCheckKevFlags(currentKev);

            String resultJson = objectMapper.writeValueAsString(Map.of("saved", saved));
            jobService.update(jobId, new UpdateJobRequest("completed", 100, resultJson, null));
            log.info("VulnCheck KEV sync completed job={} saved={}", jobId, saved);

        } catch (JobCancelledException jce) {
            log.info("VulnCheck KEV sync job={} was cancelled — stopping", jobId);
        } catch (Exception ex) {
            log.error("VulnCheck KEV sync failed job={}", jobId, ex);
            try { jobService.update(jobId, new UpdateJobRequest("failed", null, null, ex.getMessage())); }
            catch (Exception ignored) {}
        }
    }

    private String fetchBackupUrl(String apiKey) throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(VulnCheckKevService.BACKUP_ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new RuntimeException("HTTP " + response.statusCode() + " from VulnCheck backup API");

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty() || data.get(0).path("url").isMissingNode())
            throw new RuntimeException("VulnCheck backup API did not return a download URL");
        return data.get(0).path("url").asText();
    }

    /** Downloads the presigned ZIP and returns the bytes of the single JSON file inside it. */
    private byte[] downloadAndExtractJson(String presignedUrl) throws Exception {
        HttpResponse<byte[]> response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .timeout(Duration.ofSeconds(120))
                .GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
            throw new RuntimeException("HTTP " + response.statusCode() + " downloading VulnCheck KEV backup archive");

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".json")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                return out.toByteArray();
            }
        }
        throw new RuntimeException("No JSON file found in VulnCheck KEV backup archive");
    }

    /** One raw VulnCheck feed entry, still holding its CVE list — a purely in-memory parse-time
     *  shape, never persisted as-is. {@link #expand} fans each one out into the real per-CVE
     *  {@link CveKevDetail} rows this feed entry covers. */
    private record ParsedEntry(
        List<String> cve, String vendorProject, String product, String vulnerabilityName,
        String shortDescription, String requiredAction, LocalDate dueDate, LocalDate dateAdded,
        LocalDate cisaDateAdded, boolean knownRansomwareCampaignUse, boolean reportedExploitedByCanaries,
        List<String> cwes, List<String> xdbUrls, List<String> reportedExploitationUrls, Instant syncedAt) {}

    private Stream<CveKevDetail> expand(ParsedEntry p) {
        return p.cve().stream().map(cveId -> {
            CveKevDetail e = new CveKevDetail();
            e.setCveId(cveId);
            e.setSource(SOURCE);
            e.setVendorProject(p.vendorProject());
            e.setProduct(p.product());
            e.setVulnerabilityName(p.vulnerabilityName());
            e.setShortDescription(p.shortDescription());
            e.setRequiredAction(p.requiredAction());
            e.setDueDate(p.dueDate());
            e.setDateAdded(p.dateAdded());
            e.setCisaDateAdded(p.cisaDateAdded());
            e.setKnownRansomwareCampaignUse(p.knownRansomwareCampaignUse());
            e.setReportedExploitedByCanaries(p.reportedExploitedByCanaries());
            e.setCwes(p.cwes());
            e.setXdbUrls(p.xdbUrls());
            e.setReportedExploitationUrls(p.reportedExploitationUrls());
            e.setSyncedAt(p.syncedAt());
            return e;
        });
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
        existing.setDueDate(parsed.getDueDate());
        existing.setDateAdded(parsed.getDateAdded());
        existing.setCisaDateAdded(parsed.getCisaDateAdded());
        existing.setKnownRansomwareCampaignUse(parsed.isKnownRansomwareCampaignUse());
        existing.setReportedExploitedByCanaries(parsed.getReportedExploitedByCanaries());
        existing.setCwes(parsed.getCwes());
        existing.setXdbUrls(parsed.getXdbUrls());
        existing.setReportedExploitationUrls(parsed.getReportedExploitationUrls());
        existing.setSyncedAt(parsed.getSyncedAt());
        return existing;
    }

    private List<ParsedEntry> parseEntries(JsonNode vulns, Instant syncedAt) {
        List<ParsedEntry> result = new ArrayList<>();
        for (JsonNode node : vulns) {
            try {
                List<String> cves = new ArrayList<>();
                JsonNode cveNode = node.path("cve");
                if (cveNode.isArray()) {
                    for (JsonNode c : cveNode) {
                        String cve = c.asText(null);
                        if (cve != null && !cve.isBlank()) cves.add(cve.toUpperCase());
                    }
                } else if (cveNode.isTextual()) {
                    cves.add(cveNode.asText().toUpperCase());
                }
                if (cves.isEmpty()) continue;

                String requiredAction = node.path("required_action").asText(node.path("requiredAction").asText(null));
                String ransomware = node.path("knownRansomwareCampaignUse").asText(null);

                List<String> cwes = new ArrayList<>();
                JsonNode cweNode = node.path("cwes");
                if (cweNode.isArray()) {
                    for (JsonNode c : cweNode) {
                        String cwe = c.asText(null);
                        if (cwe != null && !cwe.isBlank()) cwes.add(cwe);
                    }
                }

                result.add(new ParsedEntry(
                    cves,
                    node.path("vendorProject").asText(null),
                    node.path("product").asText(null),
                    node.path("vulnerabilityName").asText(null),
                    node.path("shortDescription").asText(null),
                    requiredAction,
                    parseDate(node.path("dueDate")),
                    parseDate(node.path("date_added")),
                    parseDate(node.path("cisa_date_added")),
                    "Known".equalsIgnoreCase(ransomware),
                    node.path("reported_exploited_by_vulncheck_canaries").asBoolean(false),
                    cwes,
                    extractUrls(node.path("vulncheck_xdb")),
                    extractUrls(node.path("vulncheck_reported_exploitation")),
                    syncedAt));
            } catch (Exception ex) {
                log.warn("Failed to parse VulnCheck KEV entry: {}", ex.getMessage());
            }
        }
        return result;
    }

    /** Defensive: pulls a "url" field out of each array element regardless of the surrounding
     *  object shape — VulnCheck's xdb/reported_exploitation sub-schemas aren't fully documented. */
    private List<String> extractUrls(JsonNode arrayNode) {
        List<String> urls = new ArrayList<>();
        if (!arrayNode.isArray()) return urls;
        for (JsonNode item : arrayNode) {
            String url = item.isTextual() ? item.asText() : item.path("url").asText(null);
            if (url != null && !url.isBlank()) urls.add(url);
        }
        return urls;
    }

    private LocalDate parseDate(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        try { return LocalDate.parse(node.asText().substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobService.update(jobId, new UpdateJobRequest(status, progress, null, null)); }
        catch (Exception e) { log.warn("job update failed: {}", e.getMessage()); }
    }
}
