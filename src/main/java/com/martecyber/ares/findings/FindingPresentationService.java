package com.martecyber.ares.findings;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.common.PriorityLabels;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;
import com.martecyber.ares.kb.emailtemplates.PriorityDisplayEntry;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared finding presentation logic for every {@code {{var}}}-templated surface that needs to
 * show a finding to a human outside the app itself — the Workflows trigger context
 * ({@code trigger.finding.*}, see {@code WorkflowEventDispatcher}, via {@link #scalars}) and the
 * email-report path ({@code FindingEmailReportService}, {@code ACTION_REPORT_FINDING}, via
 * {@link #flatVars}). {@code severityLabel}/{@code severityColor} are template-specific (each
 * {@code EmailTemplate} has its own severity styling — see {@link #severityDisplay}) and so are
 * only ever added by {@link #flatVars}, never by {@link #scalars} alone — the trigger context has
 * no specific email template in play and wouldn't know whose styling to use.
 *
 * <p>Field/namespace names deliberately mirror {@code FindingAqlRegistry}'s own AQL field names
 * 1:1 ({@code code}, {@code priority}, {@code status}, {@code fields.<slug>}, {@code cve}/{@code
 * cwe}/{@code owasp}/{@code capec}/{@code attack}) — someone who already knows how to filter
 * findings in AQL shouldn't have to learn different names to template one. List-shaped data
 * (affections, references) is exposed as {@code List<Map<String,Object>>}, meant to be consumed
 * via {@code MessagingTemplate}'s {@code {{#name}}...{{/name}}} repeat blocks rather than
 * pre-formatted into one fixed string — the template author controls the HTML of each element.
 */
@Service
public class FindingPresentationService {

    /** {@code ReferenceCatalog.code} → the AQL namespace name for that catalog's references (see
     *  FindingAqlRegistry) — "ATT&CK" deliberately becomes "attack", not "att&ck": the `&` would
     *  both break {@code MessagingTemplate}'s variable-name character class and sit awkwardly
     *  un-escaped inside HTML template source. */
    private static final Map<String, String> AQL_NAMESPACE_BY_CATALOG = Map.of(
        "CVE", "cve", "CWE", "cwe", "OWASP", "owasp", "CAPEC", "capec", "ATT&CK", "attack", "URL", "url");

    /** Fallback severity badge color when an email template doesn't override it — same palette
     *  {@code SeverityTag.vue}/{@code ares-ui/src/utils/cvss.ts} use everywhere else in the app. */
    private static final Map<String, String> DEFAULT_SEVERITY_COLOR = Map.of(
        "critical", "#ef4444", "high", "#f97316", "medium", "#ca8a04", "low", "#16a34a", "info", "#3b82f6");

    private final AffectionRepository affectionRepo;
    private final ReferenceEntryRepository referenceEntryRepo;
    private final ReferenceCatalogRepository referenceCatalogRepo;

    public FindingPresentationService(AffectionRepository affectionRepo,
                                       ReferenceEntryRepository referenceEntryRepo,
                                       ReferenceCatalogRepository referenceCatalogRepo) {
        this.affectionRepo = affectionRepo;
        this.referenceEntryRepo = referenceEntryRepo;
        this.referenceCatalogRepo = referenceCatalogRepo;
    }

    /** Every {@code finding.*} template variable for one finding, flattened and ready to hand
     *  straight to {@code MessagingTemplate.render}/{@code renderHtmlSafe} — the shape the
     *  email-report path ({@code FindingEmailReportService}) builds on. {@code priorityColors}
     *  is the target {@code EmailTemplate}'s own severity-color overrides (see
     *  {@link #severityDisplay}) — null/missing falls back to the P0-P4 default. */
    public Map<String, Object> flatVars(Finding f, Map<String, PriorityDisplayEntry> priorityColors) {
        Map<String, Object> m = new LinkedHashMap<>();
        scalars(f).forEach((k, v) -> m.put("finding." + k, v));
        severityDisplay(f.getSeverity(), priorityColors).forEach((k, v) -> m.put("finding." + k, v));
        customFields(f).forEach((slug, v) -> m.put("finding.fields." + slug, v));
        m.put("finding.affections", affections(f.getId()));
        references(f.getId()).forEach((type, list) -> m.put("finding." + ("all".equals(type) ? "references" : type), list));
        return m;
    }

    /** AQL-shaped scalar fields (see class doc) — everything about a finding that isn't a list
     *  and isn't tied to a specific email template's own styling (see {@link #severityDisplay}). */
    public Map<String, Object> scalars(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("code", f.getCode());
        m.put("title", f.getTitle());
        m.put("priority", PriorityLabels.forSeverity(f.getSeverity()));
        m.put("status", f.getStatusName());
        m.put("dueDate", f.getDueDate());
        return m;
    }

    /** The {@code severityLabel}/{@code severityColor} for a raw severity name, resolved against
     *  one email template's own {@code priorityColors} override (see {@code EmailTemplate}) —
     *  the email-template equivalent of a DOCX {@code ReportTemplate}'s {@code priorityColors}.
     *  A missing map, or a level with no entry in it, falls back to the bare P0-P4 label and the
     *  default palette — used by templates' severity badge (raw {@code severity}/{@code priority}
     *  alone is just "critical"/"P0", not a display label or color; the {@code {{...}}} template
     *  engine has no conditionals to derive one itself). */
    public Map<String, Object> severityDisplay(String severity, Map<String, PriorityDisplayEntry> priorityColors) {
        String key = severity == null ? "" : severity.toLowerCase();
        PriorityDisplayEntry entry = priorityColors != null ? priorityColors.get(key) : null;
        String label = (entry != null && entry.label() != null && !entry.label().isBlank())
            ? entry.label() : PriorityLabels.forSeverity(severity);
        String color = (entry != null && entry.color() != null && !entry.color().isBlank())
            ? entry.color() : DEFAULT_SEVERITY_COLOR.getOrDefault(key, "#6b7280");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severityLabel", label);
        m.put("severityColor", color);
        return m;
    }

    /** Every {@code FindingFieldType} slug the finding has a value for (description/impact/
     *  remediation/etc., stored as Markdown in {@link Finding#getFields()}), rendered down to
     *  plain text — the {@code {{...}}} template engine's HTML-safe mode escapes whatever a
     *  substituted value contains, so handing it raw HTML would just show up as literal escaped
     *  tags. */
    public Map<String, String> customFields(Finding f) {
        Map<String, String> out = new LinkedHashMap<>();
        FindingFields.read(f).forEach((slug, markdown) -> {
            if (markdown == null || markdown.isBlank()) { out.put(slug, ""); return; }
            String html = NotificationMarkdownRenderer.toHtml(markdown);
            out.put(slug, Jsoup.parse(html).text());
        });
        return out;
    }

    /** One entry per {@link Affection} linked to the finding — code/title/description/status,
     *  plus its own affected-assets ({@code affects}) and detected-at-assets ({@code detectedAt})
     *  sub-lists (same shape {@code ReportGenerationService}'s DOCX model uses) — meant for a
     *  {@code {{#finding.affections}}...{{/finding.affections}}} template block so the author
     *  controls the per-affection HTML instead of receiving one fixed pre-formatted blob. */
    public List<Map<String, Object>> affections(Long findingId) {
        return affectionRepo.findByFindingIdWithAssets(findingId).stream()
            .map(a -> {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("code", a.getCode());
                am.put("title", a.getTitle());
                am.put("description", a.getDescription());
                am.put("status", a.getStatus());
                am.put("affects", assetList(a, "affects"));
                am.put("detectedAt", assetList(a, "detected_at"));
                return am;
            })
            .toList();
    }

    private List<Map<String, Object>> assetList(Affection a, String role) {
        return a.getAssetLinks().stream()
            .filter(al -> role.equals(al.getRole()) && al.getAsset() != null)
            .map(al -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", al.getAssetId());
                m.put("code", al.getAsset().getCode());
                m.put("type", al.getAsset().getType());
                m.put("identifier", al.getAsset().getIdentifier());
                if ("affects".equals(role)) m.put("status", al.getStatus());
                if ("detected_at".equals(role) && al.getObservedAt() != null) m.put("observedAt", al.getObservedAt());
                return m;
            })
            .toList();
    }

    /** References grouped by type, keyed by the same namespace names {@code FindingAqlRegistry}
     *  uses ({@code cve}/{@code cwe}/{@code owasp}/{@code capec}/{@code attack}/{@code url}) plus
     *  {@code all} for the unfiltered list — each usable as its own {@code {{#finding.cve}}...
     *  {{/finding.cve}}}-style block. {@code all} is always present (possibly empty); the
     *  per-type keys only exist when the finding has at least one reference of that type. */
    public Map<String, List<Map<String, Object>>> references(Long findingId) {
        List<ReferenceEntry> refs = referenceEntryRepo.findByFindingId(findingId);
        Map<Long, String> catalogCodes = referenceCatalogRepo.findAll().stream()
            .collect(Collectors.toMap(ReferenceCatalog::getId, ReferenceCatalog::getCode));

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<Map<String, Object>> all = new ArrayList<>();
        for (ReferenceEntry r : refs) {
            String catalogCode = catalogCodes.getOrDefault(r.getCatalogId(), "");
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("catalog", catalogCode);
            rm.put("title", r.getTitle());
            if (r.getDescription() != null) rm.put("description", r.getDescription());
            if (r.getUrl() != null) rm.put("url", r.getUrl());
            all.add(rm);

            String namespace = AQL_NAMESPACE_BY_CATALOG.get(catalogCode);
            if (namespace != null) grouped.computeIfAbsent(namespace, k -> new ArrayList<>()).add(rm);
        }
        grouped.put("all", all);
        return grouped;
    }
}
