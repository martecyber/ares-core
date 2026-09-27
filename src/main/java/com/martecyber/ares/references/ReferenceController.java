package com.martecyber.ares.references;

import com.martecyber.ares.common.PagedResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/reference-catalogs")
public class ReferenceController {

    private final ReferenceService service;

    public ReferenceController(ReferenceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<ReferenceCatalog> listCatalogs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.listCatalogs(page, size), c -> c);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceCatalog getCatalog(@PathVariable Long id) {
        return service.getCatalog(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReferenceCatalog createCatalog(@RequestBody CreateCatalogRequest req) {
        return service.createCatalog(req.code(), req.title(), req.metadata());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void deleteCatalog(@PathVariable Long id) {
        service.deleteCatalog(id);
    }

    @GetMapping("/{catalogId}/entries")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<ReferenceEntry> listEntries(
        @PathVariable Long catalogId,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.listEntries(catalogId, search, page, size), e -> e);
    }

    @GetMapping("/{catalogId}/entries/{entryId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry getEntry(@PathVariable Long catalogId, @PathVariable Long entryId) {
        return service.getEntry(entryId);
    }

    @PostMapping("/{catalogId}/entries/find-or-create")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry findOrCreateEntry(@PathVariable Long catalogId,
                                            @RequestBody CreateEntryRequest req) {
        return service.findOrCreateEntry(catalogId, req.title(), req.description());
    }

    @PostMapping("/{catalogId}/entries")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<ReferenceEntry> createEntry(@PathVariable Long catalogId,
                                                      @RequestBody CreateEntryRequest req) {
        ReferenceEntry created = service.createEntry(catalogId, req.title(), req.description());
        return ResponseEntity.created(
            URI.create("/api/v1/reference-catalogs/" + catalogId + "/entries/" + created.getId())
        ).body(created);
    }

    @DeleteMapping("/{catalogId}/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void deleteEntry(@PathVariable Long catalogId, @PathVariable Long entryId) {
        service.deleteEntry(entryId);
    }

    @PostMapping("/{catalogId}/entries/{entryId}/findings/{findingId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry addFinding(@PathVariable Long catalogId, @PathVariable Long entryId,
                                     @PathVariable Long findingId) {
        return service.addFinding(entryId, findingId);
    }

    @DeleteMapping("/{catalogId}/entries/{entryId}/findings/{findingId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry removeFinding(@PathVariable Long catalogId, @PathVariable Long entryId,
                                        @PathVariable Long findingId) {
        return service.removeFinding(entryId, findingId);
    }

    @PostMapping("/{catalogId}/entries/{entryId}/detections/{detectionId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry addDetection(@PathVariable Long catalogId, @PathVariable Long entryId,
                                       @PathVariable Long detectionId) {
        return service.addDetection(entryId, detectionId);
    }

    @DeleteMapping("/{catalogId}/entries/{entryId}/detections/{detectionId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry removeDetection(@PathVariable Long catalogId, @PathVariable Long entryId,
                                          @PathVariable Long detectionId) {
        return service.removeDetection(entryId, detectionId);
    }

    @PostMapping("/{catalogId}/entries/{entryId}/templates/{templateId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry addTemplate(@PathVariable Long catalogId, @PathVariable Long entryId,
                                      @PathVariable Long templateId) {
        return service.addFindingTemplate(entryId, templateId);
    }

    @DeleteMapping("/{catalogId}/entries/{entryId}/templates/{templateId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry removeTemplate(@PathVariable Long catalogId, @PathVariable Long entryId,
                                         @PathVariable Long templateId) {
        return service.removeFindingTemplate(entryId, templateId);
    }

    // ── Plain URL references ─────────────────────────────────────────────────────

    @PostMapping("/url-entries")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReferenceEntry createUrlEntry(@RequestBody CreateUrlEntryRequest req) {
        return service.findOrCreateUrlEntry(req.url(), req.title());
    }

    @GetMapping("/entries/{entryId}/favicon")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<byte[]> getFavicon(@PathVariable Long entryId) {
        ReferenceService.Favicon favicon = service.getFavicon(entryId);
        if (favicon == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(favicon.contentType()))
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
            .body(favicon.bytes());
    }

    record CreateCatalogRequest(@NotBlank String code, @NotBlank String title, String metadata) {}
    record CreateEntryRequest(@NotBlank String title, String description) {}
    record CreateUrlEntryRequest(@NotBlank String url, String title) {}
}
