package com.martecyber.ares.reporting;

import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingPresentationService;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.integrations.notifications.MessagingIntegration;
import com.martecyber.ares.integrations.notifications.MessagingIntegrationRepository;
import com.martecyber.ares.integrations.notifications.MessagingService;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.kb.emailtemplates.EmailTemplate;
import com.martecyber.ares.kb.emailtemplates.EmailTemplateRepository;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FindingEmailReportServiceTest {

    private ReportRepository reportRepo;
    private ReportFindingRepository reportFindingRepo;
    private FindingRepository findingRepo;
    private ProjectRepository projectRepo;
    private OrganizationRepository organizationRepo;
    private EmailTemplateRepository emailTemplateRepo;
    private MessagingIntegrationRepository messagingIntegrationRepo;
    private MessagingService messagingService;
    private FindingPresentationService findingPresentationService;
    private FindingEmailReportService service;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ReportRepository.class);
        reportFindingRepo = mock(ReportFindingRepository.class);
        findingRepo = mock(FindingRepository.class);
        projectRepo = mock(ProjectRepository.class);
        organizationRepo = mock(OrganizationRepository.class);
        emailTemplateRepo = mock(EmailTemplateRepository.class);
        messagingIntegrationRepo = mock(MessagingIntegrationRepository.class);
        messagingService = mock(MessagingService.class);
        findingPresentationService = mock(FindingPresentationService.class);
        service = new FindingEmailReportService(reportRepo, reportFindingRepo, findingRepo, projectRepo,
            organizationRepo, emailTemplateRepo, messagingIntegrationRepo, messagingService, findingPresentationService);
    }

    private Report report(Long id, Long orgId, String title) {
        Report r = new Report();
        r.setOrganizationId(orgId);
        r.setTitle(title);
        when(reportRepo.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void rejectsAReportWithMoreThanOneFinding() {
        report(1L, 9L, "Multi");
        when(reportFindingRepo.findByReportId(1L)).thenReturn(
            List.of(new ReportFinding(1L, 10L), new ReportFinding(1L, 11L)));

        var ex = assertThrows(ResponseStatusException.class,
            () -> service.sendEmail(1L, 5L, 6L, List.of("a@x.com"), null, null));
        assertTrue(ex.getReason().contains("exactly one finding"));
        verifyNoInteractions(messagingService);
    }

    @Test
    void rejectsAReportWithNoLinkedFindings() {
        report(1L, 9L, "Empty");
        when(reportFindingRepo.findByReportId(1L)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class,
            () -> service.sendEmail(1L, 5L, 6L, List.of("a@x.com"), null, null));
    }

    @Test
    void rendersAndSendsForASingleFindingReport() {
        report(1L, 9L, "CVE Report");
        when(reportFindingRepo.findByReportId(1L)).thenReturn(List.of(new ReportFinding(1L, 10L)));

        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(f.getTitle()).thenReturn("SQL Injection");
        when(f.getSeverity()).thenReturn("critical");
        when(f.getProjectId()).thenReturn(20L);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        when(findingPresentationService.flatVars(eq(f), any())).thenReturn(Map.of(
            "finding.title", "SQL Injection",
            "finding.severityLabel", "CRÍTICA",
            "finding.severityColor", "#ef4444",
            "finding.fields.impact", "Full DB access"));

        Project project = mock(Project.class);
        when(project.getName()).thenReturn("Acme Web");
        when(project.getCode()).thenReturn("ACME-01");
        when(projectRepo.findById(20L)).thenReturn(Optional.of(project));

        Organization org = mock(Organization.class);
        when(org.getName()).thenReturn("Acme Corp");
        when(organizationRepo.findById(9L)).thenReturn(Optional.of(org));

        EmailTemplate template = new EmailTemplate();
        template.setSubjectTemplate("{{finding.severityLabel}}: {{finding.title}} — {{project.name}}");
        template.setHtmlContent("<p>{{finding.title}} affects {{organization.name}}. Impact: {{finding.fields.impact}}</p>");
        when(emailTemplateRepo.findById(5L)).thenReturn(Optional.of(template));

        MessagingIntegration integration = new MessagingIntegration();
        integration.setKind("email");
        when(messagingIntegrationRepo.findById(6L)).thenReturn(Optional.of(integration));

        service.sendEmail(1L, 5L, 6L, List.of("soc@example.com"), List.of("cc@example.com"), null);

        var captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService).send(eq(integration), captor.capture(), eq(true));
        NotificationMessage sent = captor.getValue();
        assertFalse(sent.markdown());
        assertEquals("CRÍTICA: SQL Injection — Acme Web", sent.title());
        assertEquals("<p>SQL Injection affects Acme Corp. Impact: Full DB access</p>", sent.body());
        assertEquals(List.of("soc@example.com"), sent.to());
        assertEquals(List.of("cc@example.com"), sent.cc());
    }

    @Test
    void sendEmailForFindingSkipsTheReportEntityEntirely() {
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(f.getTitle()).thenReturn("XSS");
        when(f.getSeverity()).thenReturn("high");
        when(f.getProjectId()).thenReturn(20L);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        when(findingPresentationService.flatVars(eq(f), any())).thenReturn(Map.of(
            "finding.title", "XSS",
            "finding.severityLabel", "ALTA",
            "finding.severityColor", "#f97316"));

        Project project = mock(Project.class);
        when(project.getOrganizationId()).thenReturn(9L);
        when(project.getName()).thenReturn("Acme Web");
        when(projectRepo.findById(20L)).thenReturn(Optional.of(project));

        Organization org = mock(Organization.class);
        when(org.getName()).thenReturn("Acme Corp");
        when(organizationRepo.findById(9L)).thenReturn(Optional.of(org));

        EmailTemplate template = new EmailTemplate();
        template.setSubjectTemplate("{{finding.severityLabel}}: {{finding.title}}");
        template.setHtmlContent("<p>{{finding.title}} — {{organization.name}}</p>");
        when(emailTemplateRepo.findById(5L)).thenReturn(Optional.of(template));

        MessagingIntegration integration = new MessagingIntegration();
        integration.setKind("email");
        when(messagingIntegrationRepo.findById(6L)).thenReturn(Optional.of(integration));

        service.sendEmailForFinding(10L, 5L, 6L, List.of("soc@example.com"), null, null);

        verifyNoInteractions(reportRepo, reportFindingRepo);
        var captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService).send(eq(integration), captor.capture(), eq(true));
        assertEquals("ALTA: XSS", captor.getValue().title());
        assertEquals("<p>XSS — Acme Corp</p>", captor.getValue().body());
    }

    @Test
    void rejectsReportingADraftFinding() {
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(f.isDraft()).thenReturn(true);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        var ex = assertThrows(ResponseStatusException.class,
            () -> service.sendEmailForFinding(10L, 5L, 6L, List.of("soc@example.com"), null, null));
        assertTrue(ex.getReason().contains("published"));
        verifyNoInteractions(messagingService);
    }

    @Test
    void previewRendersSubjectAndHtmlWithoutSendingOrRequiringAnIntegration() {
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(f.getTitle()).thenReturn("XSS");
        when(f.getSeverity()).thenReturn("high");
        when(f.getProjectId()).thenReturn(20L);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        when(findingPresentationService.flatVars(eq(f), any())).thenReturn(Map.of(
            "finding.title", "XSS",
            "finding.severityLabel", "ALTA"));

        Project project = mock(Project.class);
        when(project.getName()).thenReturn("Acme Web");
        when(projectRepo.findById(20L)).thenReturn(Optional.of(project));

        EmailTemplate template = new EmailTemplate();
        template.setSubjectTemplate("{{finding.severityLabel}}: {{finding.title}}");
        template.setHtmlContent("<p>{{finding.title}} — {{project.name}}</p>");
        when(emailTemplateRepo.findById(5L)).thenReturn(Optional.of(template));

        var preview = service.previewForFinding(10L, 5L);

        assertEquals("ALTA: XSS", preview.subject());
        assertEquals("<p>XSS — Acme Web</p>", preview.html());
        verifyNoInteractions(messagingService, messagingIntegrationRepo);
    }

    @Test
    void previewRejectsADraftFinding() {
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(f.isDraft()).thenReturn(true);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));

        var ex = assertThrows(ResponseStatusException.class, () -> service.previewForFinding(10L, 5L));
        assertTrue(ex.getReason().contains("published"));
    }

    @Test
    void rejectsAnInvalidToAddress() {
        report(1L, 9L, "R");
        when(reportFindingRepo.findByReportId(1L)).thenReturn(List.of(new ReportFinding(1L, 10L)));
        Finding f = mock(Finding.class);
        when(f.getId()).thenReturn(10L);
        when(findingRepo.findById(10L)).thenReturn(Optional.of(f));
        when(emailTemplateRepo.findById(5L)).thenReturn(Optional.of(new EmailTemplate()));
        when(messagingIntegrationRepo.findById(6L)).thenReturn(Optional.of(new MessagingIntegration()));

        assertThrows(ResponseStatusException.class,
            () -> service.sendEmail(1L, 5L, 6L, List.of("not-an-address"), null, null));
        verifyNoInteractions(messagingService);
    }
}
