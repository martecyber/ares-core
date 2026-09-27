package com.martecyber.ares.findings.templates;

import com.martecyber.ares.common.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/finding-templates")
public class FindingTemplateController {

    private final FindingTemplateService service;

    public FindingTemplateController(FindingTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<FindingTemplateDto> list(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        // aql present -> discrete filter params (including q) ignored, same coexistence rule as Detection's/Finding's.
        if (aql != null && !aql.isBlank()) {
            return PagedResponse.of(service.listByAql(aql, sortBy, sortDir, page, size), t -> t);
        }
        return PagedResponse.of(service.list(q, page, size), t -> t);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<FindingTemplateDto> create(@Valid @RequestBody CreateFindingTemplateRequest req) {
        FindingTemplateDto created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/finding-templates/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto updateMeta(@PathVariable Long id,
                                         @RequestBody java.util.Map<String, String> body) {
        return service.updateMeta(id, body.get("title"), body.get("severity"));
    }

    @PatchMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto updateField(@PathVariable Long id, @PathVariable Long fieldId,
                                          @RequestBody java.util.Map<String, String> body) {
        return service.updateField(id, fieldId, body.getOrDefault("fieldText", ""));
    }

    @PostMapping("/{id}/fields")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto addField(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        Long typeId = Long.parseLong(body.get("typeId"));
        return service.addField(id, typeId, body.get("fieldText"));
    }

    @DeleteMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto removeField(@PathVariable Long id, @PathVariable Long fieldId) {
        return service.removeField(id, fieldId);
    }

    @PutMapping("/{id}/scores")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto replaceScores(@PathVariable Long id,
                                             @RequestBody java.util.List<CreateFindingTemplateRequest.ScoreInput> scores) {
        return service.replaceScores(id, scores);
    }

    @PutMapping("/{id}/references")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto replaceReferences(@PathVariable Long id,
                                                 @RequestBody java.util.List<Long> referenceIds) {
        return service.replaceReferences(id, referenceIds);
    }

    @PostMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto assignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return service.assignTag(id, tagId);
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto unassignTag(@PathVariable Long id, @PathVariable Long tagId) {
        return service.unassignTag(id, tagId);
    }

    @PostMapping("/from-finding/{findingId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<FindingTemplateDto> fromFinding(@PathVariable Long findingId,
                                                           @RequestParam(defaultValue = "false") boolean overwrite,
                                                           @RequestBody(required = false) FindingTemplateService.FromFindingRequest req) {
        FindingTemplateDto result = service.createFromFinding(findingId, req, overwrite);
        return overwrite
            ? ResponseEntity.ok(result)
            : ResponseEntity.created(URI.create("/api/v1/finding-templates/" + result.id())).body(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public java.util.Map<String, Integer> bulkDelete(@RequestBody List<Long> ids) {
        return java.util.Map.of("deleted", service.bulkDelete(ids));
    }

    // ── Export / import ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<CreateFindingTemplateRequest> exportOne(@PathVariable Long id) {
        CreateFindingTemplateRequest data = service.exportOne(id);
        String filename = "finding-template-" + id + ".json";
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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"finding-templates.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }
}
