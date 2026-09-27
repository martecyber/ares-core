package com.martecyber.ares.reporting;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingPresentationService;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.integrations.notifications.EmailAddresses;
import com.martecyber.ares.integrations.notifications.MessagingIntegration;
import com.martecyber.ares.integrations.notifications.MessagingIntegrationRepository;
import com.martecyber.ares.integrations.notifications.MessagingService;
import com.martecyber.ares.integrations.notifications.MessagingTemplate;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.kb.emailtemplates.EmailTemplate;
import com.martecyber.ares.kb.emailtemplates.EmailTemplateRepository;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.reporting.dto.EmailPreviewDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emails a report as an alternative to generating a DOCX (see {@code ReportGenerationService
 * #generateDocument} — same "second step, deliver the already-created Report" shape, just a
 * different transport). Deliberately restricted to a single-finding report: the variable model is
 * flat ({@code finding.*}), not an array — a report grouping several findings still needs the
 * DOCX path, which already has its own multi-finding template loop.
 */
@Service
public class FindingEmailReportService {

    private final ReportRepository reportRepo;
    private final ReportFindingRepository reportFindingRepo;
    private final FindingRepository findingRepo;
    private final ProjectRepository projectRepo;
    private final OrganizationRepository organizationRepo;
    private final EmailTemplateRepository emailTemplateRepo;
    private final MessagingIntegrationRepository messagingIntegrationRepo;
    private final MessagingService messagingService;
    private final FindingPresentationService findingPresentationService;

    public FindingEmailReportService(ReportRepository reportRepo, ReportFindingRepository reportFindingRepo,
                                      FindingRepository findingRepo, ProjectRepository projectRepo,
                                      OrganizationRepository organizationRepo, EmailTemplateRepository emailTemplateRepo,
                                      MessagingIntegrationRepository messagingIntegrationRepo,
                                      MessagingService messagingService,
                                      FindingPresentationService findingPresentationService) {
        this.reportRepo = reportRepo;
        this.reportFindingRepo = reportFindingRepo;
        this.findingRepo = findingRepo;
        this.projectRepo = projectRepo;
        this.organizationRepo = organizationRepo;
        this.emailTemplateRepo = emailTemplateRepo;
        this.messagingIntegrationRepo = messagingIntegrationRepo;
        this.messagingService = messagingService;
        this.findingPresentationService = findingPresentationService;
    }

    /** Manual "Deliver report" flow (see {@code ReportController#sendEmail}) — the report was
     *  already created by the usual first step ({@code ReportGenerationService#create}). */
    public void sendEmail(Long reportId, Long emailTemplateId, Long integrationId,
                           List<String> to, List<String> cc, List<String> bcc) {
        Report report = reportRepo.findById(reportId).orElseThrow(() -> NotFoundException.of("report", reportId));
        List<ReportFinding> links = reportFindingRepo.findByReportId(reportId);
        if (links.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Email delivery is only supported for a report with exactly one finding (this report has " + links.size() + ")");
        }
        Finding finding = findingRepo.findById(links.get(0).getFindingId())
            .orElseThrow(() -> NotFoundException.of("finding", links.get(0).getFindingId()));
        send(report.getTitle(), report.getOrganizationId(), finding, emailTemplateId, integrationId, to, cc, bcc);
    }

    /** {@code ACTION_REPORT_FINDING} workflow node's email mode, and the manual "Report" button
     *  on a finding (see {@code FindingController#sendEmail}) — no {@code Report} row involved at
     *  all (unlike {@link #sendEmail}): a one-shot push, not something that needs its own
     *  persisted, listable report entity the way a manually generated DOCX does. Only a published
     *  (non-draft) finding can be reported — a draft's content isn't final yet. */
    public void sendEmailForFinding(Long findingId, Long emailTemplateId, Long integrationId,
                                     List<String> to, List<String> cc, List<String> bcc) {
        Finding finding = findingRepo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        if (finding.isDraft()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a published finding can be reported");
        }
        Long organizationId = finding.getProjectId() != null
            ? projectRepo.findById(finding.getProjectId()).map(Project::getOrganizationId).orElse(null)
            : null;
        send(null, organizationId, finding, emailTemplateId, integrationId, to, cc, bcc);
    }

    /** "Preview" button in the manual "Report" dialog (see {@code FindingController#emailPreview})
     *  — renders exactly what an actual send would produce, without sending or requiring an
     *  integration/recipients to be chosen yet. Same published-only guard as {@link
     *  #sendEmailForFinding} since it's part of the same flow. */
    public EmailPreviewDto previewForFinding(Long findingId, Long emailTemplateId) {
        Finding finding = findingRepo.findById(findingId).orElseThrow(() -> NotFoundException.of("finding", findingId));
        if (finding.isDraft()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a published finding can be reported");
        }
        EmailTemplate template = emailTemplateRepo.findById(emailTemplateId)
            .orElseThrow(() -> NotFoundException.of("email template", emailTemplateId));
        Long organizationId = finding.getProjectId() != null
            ? projectRepo.findById(finding.getProjectId()).map(Project::getOrganizationId).orElse(null)
            : null;
        return render(null, organizationId, finding, template);
    }

    private void send(String reportTitle, Long organizationId, Finding finding, Long emailTemplateId, Long integrationId,
                       List<String> to, List<String> cc, List<String> bcc) {
        EmailTemplate template = emailTemplateRepo.findById(emailTemplateId)
            .orElseThrow(() -> NotFoundException.of("email template", emailTemplateId));
        MessagingIntegration integration = messagingIntegrationRepo.findById(integrationId)
            .orElseThrow(() -> NotFoundException.of("messaging integration", integrationId));

        Map<String, Object> vars = buildVars(reportTitle, organizationId, finding, template);
        EmailPreviewDto rendered = render(vars, template);

        List<String> toResolved = renderRecipients("to", to, vars);
        List<String> ccResolved = renderRecipients("cc", cc, vars);
        List<String> bccResolved = renderRecipients("bcc", bcc, vars);
        if (toResolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one 'to' address is required");
        }

        messagingService.send(integration,
            new NotificationMessage(rendered.subject(), rendered.html(), null, null, false, toResolved, ccResolved, bccResolved), true);
    }

    private EmailPreviewDto render(String reportTitle, Long organizationId, Finding finding, EmailTemplate template) {
        return render(buildVars(reportTitle, organizationId, finding, template), template);
    }

    private EmailPreviewDto render(Map<String, Object> vars, EmailTemplate template) {
        String subject = MessagingTemplate.render(template.getSubjectTemplate(), vars);
        // htmlContent is already-sanitized HTML authored via the KB email-template editor —
        // renderHtmlSafe HTML-escapes only the *substituted values* (finding title, custom
        // fields, etc. are user-controlled data), never the template's own markup.
        String html = MessagingTemplate.renderHtmlSafe(template.getHtmlContent(), vars);
        return new EmailPreviewDto(subject, html);
    }

    private Map<String, Object> buildVars(String reportTitle, Long organizationId, Finding finding, EmailTemplate template) {
        // finding.id/code/title/priority/status/dueDate/severityLabel/severityColor/fields.*/
        // affections/references(+cve/cwe/owasp/capec/attack/url). severityLabel/severityColor
        // are resolved against *this* template's own priorityColors override (mirroring a DOCX
        // ReportTemplate's priorityColors) — see FindingPresentationService#severityDisplay.
        // affections/references are Lists, meant for a
        // {{#finding.affections}}...{{/finding.affections}}-style repeat block (see
        // MessagingTemplate) rather than one fixed pre-formatted string.
        Map<String, Object> vars = new LinkedHashMap<>(findingPresentationService.flatVars(finding, template.getPriorityColors()));
        if (reportTitle != null) vars.put("report.title", reportTitle);

        if (finding.getProjectId() != null) {
            projectRepo.findById(finding.getProjectId()).ifPresent((Project p) -> {
                vars.put("project.name", p.getName());
                vars.put("project.code", p.getCode());
            });
        }
        if (organizationId != null) {
            organizationRepo.findById(organizationId).ifPresent((Organization o) ->
                vars.put("organization.name", o.getName()));
        }
        return vars;
    }

    private static List<String> renderRecipients(String fieldName, List<String> raw, Map<String, Object> vars) {
        if (raw == null || raw.isEmpty()) return List.of();
        try {
            return EmailAddresses.renderAndValidate(fieldName, String.join(",", raw), vars);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
