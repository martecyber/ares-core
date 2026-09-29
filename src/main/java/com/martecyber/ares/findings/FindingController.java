package com.martecyber.ares.findings;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.findings.dto.*;
import com.martecyber.ares.findings.templates.FindingTemplateDto;
import com.martecyber.ares.projects.rules.ReportRecipientRuleService;
import com.martecyber.ares.reporting.FindingEmailReportService;
import com.martecyber.ares.reporting.dto.EmailPreviewDto;
import com.martecyber.ares.reporting.dto.SendEmailReportRequest;
import com.martecyber.ares.reporting.dto.SuggestedRecipientsDto;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/findings")
public class FindingController {

    private final FindingService service;
    private final FindingEmailReportService emailReportSvc;
    private final ReportRecipientRuleService recipientRuleSvc;

    public FindingController(FindingService service, FindingEmailReportService emailReportSvc,
                              ReportRecipientRuleService recipientRuleSvc) {
        this.service = service;
        this.emailReportSvc = emailReportSvc;
        this.recipientRuleSvc = recipientRuleSvc;
    }

    @GetMapping("/field-types")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<FindingFieldTypeDto> listFieldTypes() {
        return service.listFieldTypes();
    }

    @PostMapping("/field-types")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<FindingFieldTypeDto> createFieldType(
        @Valid @RequestBody CreateFindingFieldTypeRequest req
    ) {
        FindingFieldTypeDto created = service.createFieldType(req);
        return ResponseEntity.created(URI.create("/api/v1/findings/field-types/" + created.id())).body(created);
    }

    @PatchMapping("/field-types/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public FindingFieldTypeDto updateFieldType(@PathVariable Long id,
                                               @RequestBody UpdateFindingFieldTypeRequest req) {
        return service.updateFieldType(id, req);
    }

    @DeleteMapping("/field-types/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deleteFieldType(@PathVariable Long id) {
        service.deleteFieldType(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/score-types")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<FindingScoreTypeDto> listScoreTypes() {
        return service.listScoreTypes();
    }

    @GetMapping("/statuses")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<FindingStatusDto> listStatuses() {
        return service.listStatuses();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public PagedResponse<FindingDto> list(
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) List<Long> projectIds,
        @RequestParam(required = false) Long organizationId,
        @RequestParam(defaultValue = "false") boolean includeDrafts,
        @RequestParam(required = false) List<String> severity,
        @RequestParam(required = false) List<Long> statusId,
        @RequestParam(required = false) String iterationLabel,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        // aql present -> discrete filter params (including q) ignored, same coexistence rule as Detection's.
        if (aql != null && !aql.isBlank()) {
            return PagedResponse.of(service.listByAql(projectId, organizationId, includeDrafts, aql, sortBy, sortDir, page, size), f -> f);
        }
        return PagedResponse.of(service.list(projectId, projectIds, organizationId, includeDrafts, severity, statusId, iterationLabel, q, page, size), f -> f);
    }

    /** Distinct assets touched by every finding matching the given filter (same params/
     *  coexistence rule as the list endpoint above) — for the "affected assets" summary panel on
     *  findings-list views. Aggregates across the whole matching set, not just one page. */
    @GetMapping("/affected-assets")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<com.martecyber.ares.affections.dto.AffectedAssetDto> affectedAssets(
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) List<Long> projectIds,
        @RequestParam(required = false) Long organizationId,
        @RequestParam(defaultValue = "false") boolean includeDrafts,
        @RequestParam(required = false) List<String> severity,
        @RequestParam(required = false) List<Long> statusId,
        @RequestParam(required = false) String iterationLabel,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String aql
    ) {
        return service.affectedAssets(projectId, projectIds, organizationId, includeDrafts, severity, statusId, iterationLabel, q, aql);
    }

    @GetMapping("/by-asset")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<FindingDto> listByAsset(
        @RequestParam Long assetId,
        @RequestParam(required = false) Long projectId,
        @RequestParam(required = false) Long organizationId
    ) {
        return service.listByAsset(assetId, projectId, organizationId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public FindingDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<FindingDto> create(@Valid @RequestBody CreateFindingRequest req) {
        FindingDto created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/findings/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto update(@PathVariable Long id, @RequestParam Long projectId,
                             @Valid @RequestBody UpdateFindingRequest req) {
        return service.update(id, projectId, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam Long projectId) {
        service.delete(id, projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/affections")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addAffection(@PathVariable Long id, @RequestParam Long projectId,
                                   @RequestBody CreateFindingRequest.AffectionRequest req) {
        return service.addAffection(id, projectId, req);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto publish(@PathVariable Long id, @RequestParam Long projectId) {
        return service.publish(id, projectId);
    }

    @PostMapping("/publish-batch")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<FindingDto> publishBatch(@RequestParam Long projectId,
                                         @Valid @RequestBody PublishBatchRequest req) {
        return service.publishBatch(projectId, req.ids());
    }

    @PostMapping("/publish-all-ready")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<FindingDto> publishAllReady(@RequestParam Long projectId) {
        return service.publishAllReady(projectId);
    }

    /** Manual "Report" button — email this finding directly, no workflow or {@code Report}
     *  entity involved (see {@code FindingEmailReportService#sendEmailForFinding}). Only a
     *  published finding can be reported; the service enforces that. */
    @PostMapping("/{id}/send-email")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> sendEmail(@PathVariable Long id, @Valid @RequestBody SendEmailReportRequest req) {
        emailReportSvc.sendEmailForFinding(id, req.emailTemplateId(), req.integrationId(), req.to(), req.cc(), req.bcc());
        return ResponseEntity.noContent().build();
    }

    /** "Preview" button in the "Report" dialog — renders the subject/HTML an actual send would
     *  produce, without sending (see {@code FindingEmailReportService#previewForFinding}). */
    @GetMapping("/{id}/email-preview")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public EmailPreviewDto emailPreview(@PathVariable Long id, @RequestParam Long emailTemplateId) {
        return emailReportSvc.previewForFinding(id, emailTemplateId);
    }

    /** Recipients to pre-fill the "Report" dialog's To/CC/BCC with, resolved from this finding's
     *  project's "email_recipients" Rules of Engagement (see {@code ReportRecipientRuleService}).
     *  Purely a suggestion — the operator can add/remove before sending. */
    @GetMapping("/{id}/suggested-recipients")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public SuggestedRecipientsDto suggestedRecipients(@PathVariable Long id) {
        return SuggestedRecipientsDto.from(recipientRuleSvc.resolve(id));
    }

    @PostMapping("/{id}/save-as-template")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingTemplateDto saveAsTemplate(@PathVariable Long id, @RequestParam Long projectId) {
        return service.saveAsTemplate(id, projectId);
    }

    @PostMapping("/{id}/ready-to-report")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto markReadyToReport(@PathVariable Long id, @RequestParam Long projectId) {
        return service.markReadyToReport(id, projectId, true);
    }

    @DeleteMapping("/{id}/ready-to-report")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto unmarkReadyToReport(@PathVariable Long id, @RequestParam Long projectId) {
        return service.markReadyToReport(id, projectId, false);
    }

    @PatchMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto updateField(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long fieldId,
                                  @RequestBody java.util.Map<String, String> body) {
        return service.updateField(id, projectId, fieldId, body.get("fieldText"));
    }

    @PostMapping("/{id}/fields")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addField(@PathVariable Long id, @RequestParam Long projectId,
                               @RequestBody java.util.Map<String, String> body) {
        Long typeId = Long.parseLong(body.get("typeId"));
        return service.addField(id, projectId, typeId, body.get("fieldText"));
    }

    @DeleteMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeField(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long fieldId) {
        return service.removeField(id, projectId, fieldId);
    }

    @PostMapping("/{id}/scores")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addScore(@PathVariable Long id, @RequestParam Long projectId,
                               @RequestBody java.util.Map<String, Object> body) {
        Long typeId = Long.parseLong(body.get("typeId").toString());
        java.math.BigDecimal score = new java.math.BigDecimal(body.get("score").toString());
        String vector = body.containsKey("vector") ? (String) body.get("vector") : null;
        boolean isDefault = body.containsKey("isDefault") && Boolean.TRUE.equals(body.get("isDefault"));
        String comment = body.containsKey("comment") ? (String) body.get("comment") : null;
        Long ssvcLeafNodeId = body.containsKey("ssvcLeafNodeId") && body.get("ssvcLeafNodeId") != null
            ? Long.parseLong(body.get("ssvcLeafNodeId").toString()) : null;
        return service.addScore(id, projectId, typeId, score, vector, isDefault, comment, ssvcLeafNodeId);
    }

    @PatchMapping("/{id}/scores/{scoreId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto updateScore(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long scoreId,
                                  @RequestBody java.util.Map<String, Object> body) {
        java.math.BigDecimal score = body.containsKey("score")
            ? new java.math.BigDecimal(body.get("score").toString()) : null;
        String vector = body.containsKey("vector") ? (String) body.get("vector") : null;
        Boolean isDefault = body.containsKey("isDefault") ? (Boolean) body.get("isDefault") : null;
        String comment = body.containsKey("comment") ? (String) body.get("comment") : null;
        boolean ssvcLeafNodeIdProvided = body.containsKey("ssvcLeafNodeId");
        Long ssvcLeafNodeId = ssvcLeafNodeIdProvided && body.get("ssvcLeafNodeId") != null
            ? Long.parseLong(body.get("ssvcLeafNodeId").toString()) : null;
        return service.updateScore(id, projectId, scoreId, score, vector, isDefault, comment,
            ssvcLeafNodeId, ssvcLeafNodeIdProvided);
    }

    @DeleteMapping("/{id}/scores/{scoreId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeScore(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long scoreId) {
        return service.removeScore(id, projectId, scoreId);
    }

    @PostMapping("/{id}/references/{referenceId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto addReference(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long referenceId) {
        return service.addReference(id, projectId, referenceId);
    }

    @DeleteMapping("/{id}/references/{referenceId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto removeReference(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long referenceId) {
        return service.removeReference(id, projectId, referenceId);
    }

    @PostMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto assignTag(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long tagId) {
        return service.assignTag(id, projectId, tagId);
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public FindingDto unassignTag(@PathVariable Long id, @RequestParam Long projectId, @PathVariable Long tagId) {
        return service.unassignTag(id, projectId, tagId);
    }
}
