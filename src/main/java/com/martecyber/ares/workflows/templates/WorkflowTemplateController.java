package com.martecyber.ares.workflows.templates;

import com.martecyber.ares.common.PagedResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/** KB endpoint for reusable workflow graphs. */
@RestController
@RequestMapping("/api/v1/workflow-templates")
public class WorkflowTemplateController {

    private final WorkflowTemplateService service;

    public WorkflowTemplateController(WorkflowTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<WorkflowTemplateDto> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(page, size), t -> t);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowTemplateDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<WorkflowTemplateDto> create(@RequestParam(defaultValue = "false") boolean overwrite,
                                                      @RequestBody WorkflowTemplateService.CreateOrUpdateRequest req) {
        WorkflowTemplateDto result = service.create(req, overwrite);
        return overwrite
            ? ResponseEntity.ok(result)
            : ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/workflow-templates/" + result.id()))
                .body(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowTemplateDto update(@PathVariable Long id,
                                      @RequestBody WorkflowTemplateService.CreateOrUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/from-workflow/{workflowId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<WorkflowTemplateDto> fromWorkflow(@PathVariable Long workflowId,
                                                             @RequestParam(defaultValue = "false") boolean overwrite,
                                                             @RequestBody(required = false) WorkflowTemplateService.FromWorkflowRequest req) {
        WorkflowTemplateDto result = service.createFromWorkflow(workflowId, req, overwrite);
        return overwrite
            ? ResponseEntity.ok(result)
            : ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/workflow-templates/" + result.id()))
                .body(result);
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
    public ResponseEntity<WorkflowTemplateService.CreateOrUpdateRequest> exportOne(@PathVariable Long id) {
        WorkflowTemplateService.CreateOrUpdateRequest data = service.exportOne(id);
        String filename = "workflow-template-" + id + ".json";
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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"workflow-templates.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public WorkflowTemplateImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }
}
