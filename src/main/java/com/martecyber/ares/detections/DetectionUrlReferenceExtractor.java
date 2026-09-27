package com.martecyber.ares.detections;

import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import com.martecyber.ares.references.ReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Links documentation/reference URLs that some scanners embed in a distinct, structured field
 * of their raw output (see {@link DetectionUrlReferenceParser}'s own doc for examples) as
 * URL-type {@link ReferenceEntry} rows — the same treatment references get when added by hand
 * on a finding (title/favicon best-effort fetched via {@link
 * ReferenceService#findOrCreateUrlEntry}).
 *
 * <p>Which tool's raw output has such a field, and where in it, is never known to ares-core —
 * that's exactly what {@link DetectionUrlReferenceParser} lets a plugin declare for its own
 * tool. A {@code sourceType} with no registered parser is simply a no-op, the same as an
 * unsupported {@code ImportParser} format elsewhere.
 */
@Component
public class DetectionUrlReferenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(DetectionUrlReferenceExtractor.class);

    private final ReferenceService referenceService;
    private final ReferenceEntryRepository entryRepo;
    /** Mutable — a plugin's own {@link DetectionUrlReferenceParser} bean(s) get added/removed at
     *  runtime via {@link #registerParser}/{@link #unregisterParser} (called from {@code
     *  PluginLoader}), same pattern as {@code ImportService}'s own parser list. */
    private final List<DetectionUrlReferenceParser> parsers;

    public DetectionUrlReferenceExtractor(ReferenceService referenceService, ReferenceEntryRepository entryRepo,
                                           List<DetectionUrlReferenceParser> parsers) {
        this.referenceService = referenceService;
        this.entryRepo = entryRepo;
        this.parsers = new CopyOnWriteArrayList<>(parsers);
    }

    /** Called by {@code PluginLoader} when a plugin contributing a {@link
     *  DetectionUrlReferenceParser} loads. */
    public void registerParser(DetectionUrlReferenceParser parser) {
        parsers.add(parser);
    }

    public void unregisterParser(DetectionUrlReferenceParser parser) {
        parsers.remove(parser);
    }

    /** No-op for a detection whose {@code sourceType} has no registered parser. */
    public void extractAndLink(Detection detection) {
        String raw = detection.getRawData();
        String sourceType = detection.getSourceType();
        if (raw == null || raw.isBlank() || sourceType == null) return;

        DetectionUrlReferenceParser parser = parsers.stream()
            .filter(p -> sourceType.equals(p.getToolId()))
            .findFirst().orElse(null);
        if (parser == null) return;

        Set<String> urls;
        try {
            urls = parser.extractUrls(raw);
        } catch (Exception e) {
            log.debug("DetectionUrlReferenceParser for '{}' threw on detection {}: {}", sourceType, detection.getId(), e.getMessage());
            return;
        }
        if (urls == null || urls.isEmpty()) return;

        for (String url : urls) {
            try {
                ReferenceEntry entry = referenceService.findOrCreateUrlEntry(url, null);
                entry.getDetections().add(detection);
                entryRepo.save(entry);
            } catch (Exception e) {
                // Malformed / unreachable / non-public URL — skip it, never fail the import over this.
                log.debug("Skipping URL reference '{}' for detection {}: {}", url, detection.getId(), e.getMessage());
            }
        }
    }
}
