package com.martecyber.ares.agents.tasks.templates;

import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
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

/** KB endpoint for reusable agent-task configurations. */
@RestController
@RequestMapping("/api/v1/agent-task-templates")
public class AgentTaskTemplateController {

    private final AgentTaskTemplateService service;
    private final AgentToolSpecRegistry agentToolSpecRegistry;

    public AgentTaskTemplateController(AgentTaskTemplateService service, AgentToolSpecRegistry agentToolSpecRegistry) {
        this.service = service;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<AgentTaskTemplateDto> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(page, size), t -> t);
    }

    /**
     * Project-agnostic tool catalog (used by the KB edit dialog, which has no
     * project context — templates only bind to a project when applied).
     */
    @GetMapping("/tools")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<AgentToolSpec> tools() {
        return agentToolSpecRegistry.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AgentTaskTemplateDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<AgentTaskTemplateDto> create(@RequestParam(defaultValue = "false") boolean overwrite,
                                                       @RequestBody AgentTaskTemplateService.CreateOrUpdateRequest req) {
        AgentTaskTemplateDto result = service.create(req, overwrite);
        return overwrite
            ? ResponseEntity.ok(result)
            : ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/agent-task-templates/" + result.id()))
                .body(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AgentTaskTemplateDto update(@PathVariable Long id,
                                       @RequestBody AgentTaskTemplateService.CreateOrUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/from-task/{taskId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<AgentTaskTemplateDto> fromTask(@PathVariable Long taskId,
                                                          @RequestParam(defaultValue = "false") boolean overwrite,
                                                          @RequestBody(required = false) AgentTaskTemplateService.FromTaskRequest req) {
        AgentTaskTemplateDto result = service.createFromTask(taskId, req, overwrite);
        return overwrite
            ? ResponseEntity.ok(result)
            : ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/agent-task-templates/" + result.id()))
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
    public ResponseEntity<AgentTaskTemplateService.CreateOrUpdateRequest> exportOne(@PathVariable Long id) {
        AgentTaskTemplateService.CreateOrUpdateRequest data = service.exportOne(id);
        String filename = "agent-task-template-" + id + ".json";
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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"agent-task-templates.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public AgentTaskTemplateImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }
}
