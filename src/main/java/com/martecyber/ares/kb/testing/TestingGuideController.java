package com.martecyber.ares.kb.testing;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.kb.testing.dto.TestingGuideDto;
import com.martecyber.ares.kb.testing.dto.TestingGuidePointDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/** KB endpoint for reusable testing checklists (guides + their points). */
@RestController
@RequestMapping("/api/v1/kb/testing-guides")
public class TestingGuideController {

    private final TestingGuideService service;

    public TestingGuideController(TestingGuideService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<TestingGuideDto> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(page, size), g -> g);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingGuideDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<TestingGuideDto> create(@RequestBody TestingGuideService.GuideRequest req) {
        TestingGuideDto created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/kb/testing-guides/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingGuideDto update(@PathVariable Long id,
                                  @RequestBody TestingGuideService.GuideRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Export / import ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<ImportGuideRequest> exportOne(@PathVariable Long id) {
        ImportGuideRequest data = service.exportOne(id);
        String filename = "testing-guide-" + id + ".json";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(data);
    }

    @PostMapping("/export-zip")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<byte[]> exportZip(@RequestBody List<Long> ids) {
        byte[] zip = service.exportZip(ids);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"testing-guides.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    /** Accepts one or more .json files (one guide each) or .zip files (a batch, as
     *  produced by export-zip). Creates a new guide (with all its points) per entry. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingGuideImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }

    public record ImportGuideRequest(
        String name,
        String description,
        java.util.List<ImportPointRequest> points
    ) {}

    public record ImportPointRequest(
        String title,
        String description,
        int sortOrder
    ) {}

    // ── Points ─────────────────────────────────────────────────────────────

    @PostMapping("/{guideId}/points")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public TestingGuidePointDto addPoint(@PathVariable Long guideId,
                                         @RequestBody TestingGuideService.PointRequest req) {
        return service.addPoint(guideId, req);
    }

    @PutMapping("/{guideId}/points/{pointId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingGuidePointDto updatePoint(@PathVariable Long guideId,
                                            @PathVariable Long pointId,
                                            @RequestBody TestingGuideService.PointRequest req) {
        return service.updatePoint(guideId, pointId, req);
    }

    @DeleteMapping("/{guideId}/points/{pointId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePoint(@PathVariable Long guideId, @PathVariable Long pointId) {
        service.deletePoint(guideId, pointId);
    }

    @PostMapping("/{guideId}/points/reorder")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<TestingGuidePointDto> reorder(@PathVariable Long guideId,
                                              @RequestBody ReorderRequest req) {
        return service.reorder(guideId, req.orderedIds());
    }

    public record ReorderRequest(List<Long> orderedIds) {}
}
