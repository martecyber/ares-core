package com.martecyber.ares.kb.emailtemplates;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.emailtemplates.dto.EmailTemplateDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CRUD for KB email templates. {@code htmlContent} is sanitized server-side on every create/
 * update — the frontend editor's preview sanitization (DOMPurify) is a UX convenience only, this
 * is the real security boundary. Sanitization strips script execution vectors (script/iframe/
 * object/embed/form, on*-handlers, javascript: URLs) while keeping the tags/attributes typical of
 * email-HTML layouts (tables, inline styles). CSS values inside {@code style} attributes aren't
 * deep-parsed for legacy vectors like CSS expression() — those are dead in modern rendering
 * engines and email clients never execute script regardless.
 */
@Service
public class EmailTemplateService {

    private static final Safelist EMAIL_SAFELIST = Safelist.relaxed()
        .addTags("font", "center", "hr")
        .addAttributes(":all", "style", "class", "align", "valign", "width", "height",
            "bgcolor", "cellpadding", "cellspacing", "border", "color", "background")
        .addProtocols("img", "src", "data", "cid");

    private final EmailTemplateRepository repo;

    public EmailTemplateService(EmailTemplateRepository repo) {
        this.repo = repo;
    }

    public Page<EmailTemplateDto> list(String q, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        String query = (q != null && !q.isBlank()) ? q.trim() : null;
        Page<EmailTemplate> src = query != null ? repo.search(query, p) : repo.findAllByOrderByNameAsc(p);
        return src.map(EmailTemplateDto::summary);
    }

    public EmailTemplateDto get(Long id) {
        return EmailTemplateDto.detail(load(id));
    }

    public EmailTemplateDto create(TemplateRequest req) {
        requireName(req);
        OffsetDateTime now = OffsetDateTime.now();
        EmailTemplate t = new EmailTemplate();
        t.setName(req.name().trim());
        t.setSubjectTemplate(req.subjectTemplate());
        t.setHtmlContent(sanitize(req.htmlContent()));
        t.setPriorityColors(req.priorityColors());
        t.setCreatorId(currentUserId());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        repo.save(t);
        return EmailTemplateDto.detail(t);
    }

    public EmailTemplateDto update(Long id, TemplateRequest req) {
        EmailTemplate t = load(id);
        requireName(req);
        t.setName(req.name().trim());
        t.setSubjectTemplate(req.subjectTemplate());
        t.setHtmlContent(sanitize(req.htmlContent()));
        t.setPriorityColors(req.priorityColors());
        t.setUpdatedAt(OffsetDateTime.now());
        repo.save(t);
        return EmailTemplateDto.detail(t);
    }

    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("email_template", id);
        repo.deleteById(id);
    }

    private EmailTemplate load(Long id) {
        return repo.findById(id).orElseThrow(() -> NotFoundException.of("email_template", id));
    }

    private static void requireName(TemplateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
    }

    private static String sanitize(String html) {
        if (html == null || html.isBlank()) return html;
        Document dirty = Jsoup.parse(html);
        Document clean = new Cleaner(EMAIL_SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    private static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    public record TemplateRequest(String name, String subjectTemplate, String htmlContent,
                                   Map<String, PriorityDisplayEntry> priorityColors) {}
}
