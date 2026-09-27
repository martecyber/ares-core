package com.martecyber.ares.kb.emailtemplates;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.kb.emailtemplates.dto.EmailTemplateDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/** KB endpoint for reusable HTML email templates used to deliver a finding as a report by email
 *  (Workflows {@code ACTION_REPORT_FINDING}, email mode). */
@RestController
@RequestMapping("/api/v1/kb/email-templates")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<EmailTemplateDto> list(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(q, page, size), p -> p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public EmailTemplateDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<EmailTemplateDto> create(@RequestBody EmailTemplateService.TemplateRequest req) {
        EmailTemplateDto created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/kb/email-templates/" + created.id()))
            .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public EmailTemplateDto update(@PathVariable Long id, @RequestBody EmailTemplateService.TemplateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
