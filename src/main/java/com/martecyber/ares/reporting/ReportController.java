package com.martecyber.ares.reporting;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.reporting.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class ReportController {

    private final ReportService svc;
    private final ReportTemplateService templateSvc;
    private final ReportGenerationService generationSvc;
    private final ReportFieldTypeService fieldTypeSvc;
    private final FindingEmailReportService emailReportSvc;

    public ReportController(ReportService svc, ReportTemplateService templateSvc,
                             ReportGenerationService generationSvc,
                             ReportFieldTypeService fieldTypeSvc,
                             FindingEmailReportService emailReportSvc) {
        this.svc = svc;
        this.templateSvc = templateSvc;
        this.generationSvc = generationSvc;
        this.fieldTypeSvc = fieldTypeSvc;
        this.emailReportSvc = emailReportSvc;
    }

    // ── Reports ────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/reports")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<ReportDto> list(
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(svc.list(organizationId, projectId, status, page, size));
    }

    @GetMapping("/api/v1/reports/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public ReportDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportDto create(@Valid @RequestBody GenerateReportRequest req) {
        return generationSvc.create(req);
    }

    @PostMapping("/api/v1/reports/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportDto generateLegacy(@Valid @RequestBody GenerateReportRequest req) {
        return generationSvc.create(req);
    }

    @PostMapping("/api/v1/reports/{id}/generate-document")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportDto generateDocument(@PathVariable Long id,
                                       @RequestBody(required = false) java.util.Map<String, Object> body) {
        Long templateId = (body != null && body.containsKey("templateId"))
            ? Long.parseLong(body.get("templateId").toString()) : null;
        return generationSvc.generateDocument(id, templateId);
    }

    /** Alternative to {@code generate-document} — emails the already-created report (via a KB
     *  {@code EmailTemplate}) instead of rendering a DOCX. Only valid for a single-finding report. */
    @PostMapping("/api/v1/reports/{id}/send-email")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> sendEmail(@PathVariable Long id, @Valid @RequestBody SendEmailReportRequest req) {
        emailReportSvc.sendEmail(id, req.emailTemplateId(), req.integrationId(), req.to(), req.cc(), req.bcc());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/reports/{id}/export-json")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<java.util.Map<String, Object>> exportJson(@PathVariable Long id) {
        java.util.Map<String, Object> data = generationSvc.exportJson(id);
        String filename = "report_" + id + ".json";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(data);
    }

    @PostMapping("/api/v1/reports/{id}/publish")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportDto publish(@PathVariable Long id) { return svc.publish(id); }

    @PostMapping("/api/v1/reports/{id}/documents")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportDto uploadDocument(@PathVariable Long id,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        return svc.uploadDocument(id, file);
    }

    @GetMapping("/api/v1/reports/{id}/download")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        byte[] bytes = svc.download(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report_" + id + ".docx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(bytes);
    }

    @DeleteMapping("/api/v1/reports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    // ── Report templates ───────────────────────────────────────────────────────

    @GetMapping("/api/v1/report-templates")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ReportTemplateDto> listTemplates(
        @RequestParam(required = false) Long projectTypeId
    ) {
        if (projectTypeId != null) return templateSvc.listForProjectType(projectTypeId);
        return templateSvc.listAll();
    }

    @GetMapping("/api/v1/report-templates/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportTemplateDto getTemplate(@PathVariable Long id) { return templateSvc.get(id); }

    @PostMapping("/api/v1/report-templates")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<ReportTemplateDto> uploadTemplate(
        @RequestParam("file") MultipartFile file,
        @RequestParam("name") String name,
        @RequestParam(value = "description", required = false, defaultValue = "") String description,
        @RequestParam(value = "isGeneric", required = false, defaultValue = "false") boolean isGeneric
    ) throws IOException {
        ReportTemplateDto created = templateSvc.upload(file, name, description, isGeneric);
        return ResponseEntity.created(URI.create("/api/v1/report-templates/" + created.id())).body(created);
    }

    @PatchMapping("/api/v1/report-templates/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReportTemplateDto updateTemplate(@PathVariable Long id,
                                             @RequestBody UpdateReportTemplateRequest req) {
        return templateSvc.update(id, req);
    }

    @PostMapping("/api/v1/report-templates/{id}/file")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReportTemplateDto updateTemplateFile(@PathVariable Long id,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        return templateSvc.updateFile(id, file);
    }

    @DeleteMapping("/api/v1/report-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void deleteTemplate(@PathVariable Long id) { templateSvc.delete(id); }

    @GetMapping("/api/v1/report-templates/{id}/download")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable Long id) {
        byte[] bytes = templateSvc.downloadTemplate(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"template_" + id + ".docx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(bytes);
    }

    @GetMapping("/api/v1/report-templates/{id}/analyze")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<Map<String, Object>> analyzeTemplate(@PathVariable Long id) throws Exception {
        return generationSvc.analyzeTemplate(id);
    }

    // ── Report field types (admin) ─────────────────────────────────────────────

    @GetMapping("/api/v1/report-field-types")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<ReportFieldTypeDto> listFieldTypes() { return fieldTypeSvc.listAll(); }

    @GetMapping("/api/v1/report-field-types/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ReportFieldTypeDto getFieldType(@PathVariable Long id) { return fieldTypeSvc.get(id); }

    @PostMapping("/api/v1/report-field-types")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<ReportFieldTypeDto> createFieldType(@RequestBody Map<String, Object> body) {
        ReportFieldTypeDto created = fieldTypeSvc.create(body);
        return ResponseEntity.created(URI.create("/api/v1/report-field-types/" + created.id())).body(created);
    }

    @PatchMapping("/api/v1/report-field-types/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReportFieldTypeDto updateFieldType(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return fieldTypeSvc.update(id, body);
    }

    @DeleteMapping("/api/v1/report-field-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void deleteFieldType(@PathVariable Long id) { fieldTypeSvc.delete(id); }

    // ── Report field templates (admin sub-resource) ────────────────────────────

    @PostMapping("/api/v1/report-field-types/{typeId}/templates")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReportFieldTypeDto.FieldTemplateDto createFieldTemplate(
        @PathVariable Long typeId, @RequestBody Map<String, Object> body) {
        return fieldTypeSvc.createTemplate(typeId, body);
    }

    @PatchMapping("/api/v1/report-field-types/{typeId}/templates/{tplId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ReportFieldTypeDto.FieldTemplateDto updateFieldTemplate(
        @PathVariable Long typeId, @PathVariable Long tplId, @RequestBody Map<String, Object> body) {
        return fieldTypeSvc.updateTemplate(typeId, tplId, body);
    }

    @DeleteMapping("/api/v1/report-field-types/{typeId}/templates/{tplId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public void deleteFieldTemplate(@PathVariable Long typeId, @PathVariable Long tplId) {
        fieldTypeSvc.deleteTemplate(typeId, tplId);
    }
}
