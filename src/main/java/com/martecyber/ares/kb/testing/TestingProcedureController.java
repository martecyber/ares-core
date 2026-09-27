package com.martecyber.ares.kb.testing;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.kb.testing.dto.TestingProcedureDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/** KB endpoint for testing procedures (rich-text how-to articles). */
@RestController
@RequestMapping("/api/v1/kb/testing-procedures")
public class TestingProcedureController {

    private final TestingProcedureService service;

    public TestingProcedureController(TestingProcedureService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<TestingProcedureDto> list(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Long guidePointId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        if (guidePointId != null) {
            List<TestingProcedureDto> items = service.listByGuidePoint(guidePointId);
            return new PagedResponse<>(items, 0, items.size(), items.size(), 1, false);
        }
        return PagedResponse.of(service.list(q, page, size), p -> p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingProcedureDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<TestingProcedureDto> create(@RequestBody TestingProcedureService.ProcedureRequest req) {
        TestingProcedureDto created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/kb/testing-procedures/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingProcedureDto update(@PathVariable Long id,
                                      @RequestBody TestingProcedureService.ProcedureRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deleteBatch(@RequestBody java.util.List<Long> ids) {
        service.deleteBatch(ids);
        return ResponseEntity.noContent().build();
    }

    // ── Export / import ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<TestingProcedureService.ProcedureRequest> exportOne(@PathVariable Long id) {
        TestingProcedureService.ProcedureRequest data = service.exportOne(id);
        String filename = "testing-procedure-" + id + ".json";
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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"testing-procedures.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TestingProcedureImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }
}
