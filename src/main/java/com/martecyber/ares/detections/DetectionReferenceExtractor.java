package com.martecyber.ares.detections;

import com.martecyber.ares.aql.materialize.KbMaterializationService;
import com.martecyber.ares.kb.cve.CveRepository;
import com.martecyber.ares.kb.cwe.CweRepository;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a detection's raw scanner output for well-known vulnerability identifiers
 * (CVE, CWE) and links them as {@link ReferenceEntry} rows. Matching is done against
 * the raw JSON as plain text rather than against tool-specific field paths, so the
 * same logic works regardless of which scanner produced the detection (nuclei,
 * Tenable, Qualys once supported, etc.) — every tool's raw output ends up scanned
 * the same way.
 */
@Component
public class DetectionReferenceExtractor {

    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d{4,7}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CWE = Pattern.compile("CWE-\\d{1,4}", Pattern.CASE_INSENSITIVE);

    private final ReferenceCatalogRepository catalogRepo;
    private final ReferenceEntryRepository entryRepo;
    private final KbMaterializationService materializationService;
    private final CveRepository cveRepo;
    private final CweRepository cweRepo;

    public DetectionReferenceExtractor(ReferenceCatalogRepository catalogRepo, ReferenceEntryRepository entryRepo,
                                        KbMaterializationService materializationService,
                                        CveRepository cveRepo, CweRepository cweRepo) {
        this.catalogRepo = catalogRepo;
        this.entryRepo = entryRepo;
        this.materializationService = materializationService;
        this.cveRepo = cveRepo;
        this.cweRepo = cweRepo;
    }

    /** Extracts CVE/CWE ids from {@code detection.rawData} and links them. No-op if none are found. */
    public void extractAndLink(Detection detection) {
        String raw = detection.getRawData();
        if (raw == null || raw.isBlank()) return;

        link(detection, "CVE", matchAll(CVE, raw));
        link(detection, "CWE", matchAll(CWE, raw));
    }

    private void link(Detection detection, String catalogCode, Set<String> ids) {
        if (ids.isEmpty()) return;
        ReferenceCatalog catalog = catalogRepo.findByCode(catalogCode).orElse(null);
        if (catalog == null) return; // catalog not seeded — skip rather than fail the import
        for (String id : ids) {
            ReferenceEntry entry = entryRepo.findByCatalogIdAndTitle(catalog.getId(), id).orElseGet(() -> {
                ReferenceEntry e = new ReferenceEntry();
                e.setCatalogId(catalog.getId());
                e.setTitle(id);
                // Mirrors ReferencesDialog.vue's convention for a manually-added reference (a
                // CVE's own truncated description; a CWE's name) — best-effort, stays null when
                // the id isn't (yet) in the locally-synced KB corpus.
                e.setDescription(resolveDescription(catalogCode, id));
                return entryRepo.save(e);
            });
            entry.getDetections().add(detection);
            entryRepo.save(entry);
            // Lazily materialize hot KB fields for this reference (AQL implementation plan,
            // V145/Phase 2) — only CVE has any defined yet; the service itself is a no-op for
            // other catalogs and for CVEs not (yet) in the synced corpus.
            if ("CVE".equals(catalogCode)) {
                materializationService.materializeIfAbsent(id);
            }
        }
    }

    private String resolveDescription(String catalogCode, String id) {
        if ("CVE".equals(catalogCode)) {
            return cveRepo.findByCveId(id).map(c -> truncate(c.getDescription())).orElse(null);
        }
        if ("CWE".equals(catalogCode)) {
            String bare = id.startsWith("CWE-") ? id.substring(4) : id;
            return cweRepo.findByCweId(bare).map(w -> w.getName()).orElse(null);
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static Set<String> matchAll(Pattern pattern, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group().toUpperCase());
        return out;
    }
}
