package com.martecyber.ares.references;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.templates.FindingTemplateRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

@Service
public class ReferenceService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceService.class);
    private static final String URL_CATALOG_CODE = "URL";

    private final ReferenceCatalogRepository catalogRepo;
    private final ReferenceEntryRepository entryRepo;
    private final FindingRepository findingRepo;
    private final DetectionRepository detectionRepo;
    private final FindingTemplateRepository templateRepo;
    private final UrlMetadataFetcher urlMetadataFetcher;
    private final S3Client s3;

    @Value("${ares.storage.s3.buckets.favicons}")
    private String faviconBucket;

    public ReferenceService(ReferenceCatalogRepository catalogRepo,
                            ReferenceEntryRepository entryRepo,
                            FindingRepository findingRepo,
                            DetectionRepository detectionRepo,
                            FindingTemplateRepository templateRepo,
                            UrlMetadataFetcher urlMetadataFetcher,
                            S3Client s3) {
        this.catalogRepo = catalogRepo;
        this.entryRepo = entryRepo;
        this.findingRepo = findingRepo;
        this.detectionRepo = detectionRepo;
        this.templateRepo = templateRepo;
        this.urlMetadataFetcher = urlMetadataFetcher;
        this.s3 = s3;
    }

    @PostConstruct
    public void ensureFaviconBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(faviconBucket).build());
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket '{}' not found, creating it…", faviconBucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(faviconBucket).build());
        } catch (Exception e) {
            log.warn("Could not verify/create S3 bucket '{}': {}", faviconBucket, e.getMessage());
        }
    }

    public Page<ReferenceCatalog> listCatalogs(int page, int size) {
        return catalogRepo.findAll(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    public ReferenceCatalog getCatalog(Long id) {
        return catalogRepo.findById(id).orElseThrow(() -> NotFoundException.of("reference_catalog", id));
    }

    @Transactional
    public ReferenceCatalog createCatalog(String code, String title, String metadata) {
        ReferenceCatalog c = new ReferenceCatalog();
        c.setCode(code);
        c.setTitle(title);
        c.setMetadata(metadata);
        return catalogRepo.save(c);
    }

    @Transactional
    public void deleteCatalog(Long id) {
        if (!catalogRepo.existsById(id)) throw NotFoundException.of("reference_catalog", id);
        catalogRepo.deleteById(id);
    }

    public Page<ReferenceEntry> listEntries(Long catalogId, String search, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        if (search != null && !search.isBlank()) {
            return entryRepo.findByCatalogIdAndTitleContainingIgnoreCase(catalogId, search.trim(), p);
        }
        return entryRepo.findByCatalogId(catalogId, p);
    }

    public ReferenceEntry getEntry(Long id) {
        return entryRepo.findById(id).orElseThrow(() -> NotFoundException.of("reference_entry", id));
    }

    @Transactional
    public ReferenceEntry findOrCreateEntry(Long catalogId, String title, String description) {
        return entryRepo.findByCatalogIdAndTitle(catalogId, title)
            .orElseGet(() -> createEntry(catalogId, title, description));
    }

    @Transactional
    public ReferenceEntry createEntry(Long catalogId, String title, String description) {
        if (!catalogRepo.existsById(catalogId)) throw NotFoundException.of("reference_catalog", catalogId);
        ReferenceEntry e = new ReferenceEntry();
        e.setCatalogId(catalogId);
        e.setTitle(title);
        e.setDescription(description);
        return entryRepo.save(e);
    }

    @Transactional
    public void deleteEntry(Long id) {
        if (!entryRepo.existsById(id)) throw NotFoundException.of("reference_entry", id);
        entryRepo.deleteById(id);
    }

    @Transactional
    public ReferenceEntry addFinding(Long entryId, Long findingId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        var finding = findingRepo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        e.getFindings().add(finding);
        return entryRepo.save(e);
    }

    @Transactional
    public ReferenceEntry removeFinding(Long entryId, Long findingId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        e.getFindings().removeIf(f -> f.getId().equals(findingId));
        return entryRepo.save(e);
    }

    @Transactional
    public ReferenceEntry addDetection(Long entryId, Long detectionId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        var detection = detectionRepo.findById(detectionId).orElseThrow(() -> NotFoundException.of("detection", detectionId));
        e.getDetections().add(detection);
        return entryRepo.save(e);
    }

    @Transactional
    public ReferenceEntry removeDetection(Long entryId, Long detectionId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        e.getDetections().removeIf(d -> d.getId().equals(detectionId));
        return entryRepo.save(e);
    }

    @Transactional
    public ReferenceEntry addFindingTemplate(Long entryId, Long templateId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        var template = templateRepo.findById(templateId).orElseThrow(() -> NotFoundException.of("finding_template", templateId));
        e.getFindingTemplates().add(template);
        return entryRepo.save(e);
    }

    @Transactional
    public ReferenceEntry removeFindingTemplate(Long entryId, Long templateId) {
        ReferenceEntry e = entryRepo.findById(entryId).orElseThrow(() -> NotFoundException.of("reference_entry", entryId));
        e.getFindingTemplates().removeIf(t -> t.getId().equals(templateId));
        return entryRepo.save(e);
    }

    // ── URL references ───────────────────────────────────────────────────────────

    /**
     * Finds or creates a plain-URL reference. Always attempts a best-effort fetch of
     * the page to grab a favicon; the title is only fetched from the page when
     * {@code providedTitle} is blank — an explicit title is never overwritten. Fetch
     * failures (unreachable, non-public, timeout…) are swallowed: the entry still gets
     * created with whatever title was provided (or blank) and no favicon.
     */
    @Transactional
    public ReferenceEntry findOrCreateUrlEntry(String rawUrl, String providedTitle) {
        var uri = UrlMetadataFetcher.validate(rawUrl); // throws IllegalArgumentException on bad/non-public URLs
        String normalizedUrl = uri.toString();

        ReferenceCatalog catalog = catalogRepo.findByCode(URL_CATALOG_CODE)
            .orElseThrow(() -> new IllegalStateException("URL reference catalog not seeded"));

        var existing = entryRepo.findByCatalogIdAndUrl(catalog.getId(), normalizedUrl);
        if (existing.isPresent()) return existing.get();

        UrlMetadataFetcher.Metadata meta;
        try {
            meta = urlMetadataFetcher.fetch(normalizedUrl);
        } catch (Exception e) {
            meta = UrlMetadataFetcher.Metadata.EMPTY;
        }

        ReferenceEntry entry = new ReferenceEntry();
        entry.setCatalogId(catalog.getId());
        entry.setUrl(normalizedUrl);

        String title = providedTitle != null ? providedTitle.strip() : "";
        if (title.isEmpty() && meta.title() != null) title = meta.title();
        entry.setTitle(title);

        entry = entryRepo.save(entry);

        if (meta.faviconBytes() != null) {
            try {
                storeFavicon(entry, meta.faviconBytes(), meta.faviconContentType());
                entry = entryRepo.save(entry);
            } catch (Exception e) {
                log.warn("Failed to store favicon for reference_entry {}: {}", entry.getId(), e.getMessage());
            }
        }
        return entry;
    }

    private void storeFavicon(ReferenceEntry entry, byte[] bytes, String contentType) {
        String objectKey = "reference-entry/" + entry.getId() + "/" + UUID.randomUUID();
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(faviconBucket)
                .key(objectKey)
                .contentType(contentType != null ? contentType : "image/x-icon")
                .build(),
            RequestBody.fromBytes(bytes)
        );
        entry.setFaviconBucket(faviconBucket);
        entry.setFaviconObjectKey(objectKey);
        entry.setFaviconContentType(contentType != null ? contentType : "image/x-icon");
    }

    public record Favicon(byte[] bytes, String contentType) {}

    /** Favicon bytes + content type for {@code GET /reference-catalogs/entries/{id}/favicon}. Null if none stored. */
    public Favicon getFavicon(Long entryId) {
        ReferenceEntry entry = getEntry(entryId);
        if (entry.getFaviconObjectKey() == null) return null;
        byte[] bytes = s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(entry.getFaviconBucket()).key(entry.getFaviconObjectKey()).build()
        ).asByteArray();
        return new Favicon(bytes, entry.getFaviconContentType() != null ? entry.getFaviconContentType() : "image/x-icon");
    }
}
