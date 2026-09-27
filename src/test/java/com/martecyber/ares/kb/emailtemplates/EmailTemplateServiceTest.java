package com.martecyber.ares.kb.emailtemplates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Sanitization is the real security boundary for KB email templates (the frontend editor's
 *  DOMPurify preview is a UX convenience only) — these tests exercise EmailTemplateService's
 *  server-side Jsoup safelist directly. */
class EmailTemplateServiceTest {

    private EmailTemplateRepository repo;
    private EmailTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(EmailTemplateRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new EmailTemplateService(repo);
    }

    private String savedHtml(String rawHtml) {
        service.create(new EmailTemplateService.TemplateRequest("t", "Subject {{x}}", rawHtml, null));
        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        verify(repo).save(captor.capture());
        return captor.getValue().getHtmlContent();
    }

    @Test
    void stripsScriptTags() {
        String out = savedHtml("<p>hi</p><script>alert(1)</script>");
        assertFalse(out.toLowerCase().contains("<script"));
        assertTrue(out.contains("<p>hi</p>"));
    }

    @Test
    void stripsOnEventAttributes() {
        String out = savedHtml("<img src=\"x.png\" onerror=\"alert(1)\">");
        assertFalse(out.toLowerCase().contains("onerror"));
    }

    @Test
    void stripsJavascriptUrls() {
        String out = savedHtml("<a href=\"javascript:alert(1)\">click</a>");
        assertFalse(out.toLowerCase().contains("javascript:"));
    }

    @Test
    void stripsIframeAndFormTags() {
        String out = savedHtml("<iframe src=\"evil.html\"></iframe><form action=\"x\"><input></form>");
        assertFalse(out.toLowerCase().contains("<iframe"));
        assertFalse(out.toLowerCase().contains("<form"));
    }

    @Test
    void keepsEmailLayoutTagsAndInlineStyle() {
        String out = savedHtml(
            "<table cellpadding=\"0\" cellspacing=\"0\" width=\"600\"><tr><td style=\"color:red;\">Hi</td></tr></table>");
        assertTrue(out.contains("<table"));
        assertTrue(out.contains("style=\"color:red;\""));
        assertTrue(out.contains("cellpadding=\"0\""));
    }

    @Test
    void keepsDataAndCidImageSources() {
        String out = savedHtml("<img src=\"data:image/png;base64,abc123\">");
        assertTrue(out.contains("data:image/png;base64,abc123"));
    }

    @Test
    void requiresName() {
        assertThrows(Exception.class, () ->
            service.create(new EmailTemplateService.TemplateRequest(" ", "s", "<p>x</p>", null)));
    }

    @Test
    void persistsPriorityColors() {
        var colors = java.util.Map.of("critical", new PriorityDisplayEntry("CRITICAL", "#ef4444"));
        service.create(new EmailTemplateService.TemplateRequest("t", "s", "<p>x</p>", colors));

        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        verify(repo).save(captor.capture());
        assertEquals(colors, captor.getValue().getPriorityColors());
    }
}
