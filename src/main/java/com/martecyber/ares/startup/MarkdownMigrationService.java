package com.martecyber.ares.startup;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingFields;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.templates.FindingTemplateField;
import com.martecyber.ares.findings.templates.FindingTemplateFieldRepository;
import com.martecyber.ares.kb.testing.TestingProcedure;
import com.martecyber.ares.kb.testing.TestingProcedureRepository;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-time backfill: rich-text fields used to be stored as raw Quill HTML and are
 * now stored as Markdown (see {@code ReportGenerationService.markdownToHtml} on the
 * render side). Existing rows still hold HTML from before this change, so on every
 * boot we convert any row that still looks like HTML to Markdown via flexmark's
 * HTML-to-Markdown converter, in place.
 *
 * Idempotent by construction: {@link #looksLikeHtml} only matches literal HTML tags,
 * and real Markdown output never contains them, so a row is only ever converted
 * once — subsequent boots just find nothing left to do (fast no-op).
 */
@Component
public class MarkdownMigrationService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownMigrationService.class);

    // Matches the tags Quill's default toolbar actually emits (p/div/br/strong/em/u/s/
    // ul/ol/li/blockquote/pre/code/a/img/h1-h6/span). Deliberately narrow so plain-text
    // content that merely contains a literal "<" or "<3" never false-positives.
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
        "</?(?:p|div|br|strong|b|em|i|u|s|ul|ol|li|blockquote|pre|code|a|img|h[1-6]|span)\\b[^>]*>",
        Pattern.CASE_INSENSITIVE
    );

    private static final FlexmarkHtmlConverter HTML_TO_MD = FlexmarkHtmlConverter.builder().build();

    private final FindingRepository findingRepo;
    private final AffectionRepository affectionRepo;
    private final FindingTemplateFieldRepository findingTemplateFieldRepo;
    private final TestingProcedureRepository testingProcedureRepo;

    public MarkdownMigrationService(FindingRepository findingRepo,
                                     AffectionRepository affectionRepo,
                                     FindingTemplateFieldRepository findingTemplateFieldRepo,
                                     TestingProcedureRepository testingProcedureRepo) {
        this.findingRepo = findingRepo;
        this.affectionRepo = affectionRepo;
        this.findingTemplateFieldRepo = findingTemplateFieldRepo;
        this.testingProcedureRepo = testingProcedureRepo;
    }

    /** Counts of rows actually converted in one run, per entity — returned so both the
     * boot-time caller and the manual admin trigger can report what happened. */
    public record MigrationResult(int findingFields, int affections, int findingTemplateFields, int testingProcedures) {
        public int total() { return findingFields + affections + findingTemplateFields + testingProcedures; }
    }

    @Transactional
    public MigrationResult migrateLegacyHtmlToMarkdown() {
        return new MigrationResult(
            migrateFindingFields(),
            migrateAffections(),
            migrateFindingTemplateFields(),
            migrateTestingProcedures()
        );
    }

    private static boolean looksLikeHtml(String text) {
        return text != null && !text.isBlank() && HTML_TAG_PATTERN.matcher(text).find();
    }

    private static String toMarkdown(String html) {
        String md = HTML_TO_MD.convert(html);
        return md != null ? md.trim() : "";
    }

    /** Operates on finding.fields jsonb (AQL implementation plan, V141) rather than the old
     *  finding_field EAV table — that table no longer feeds anything the app reads, so
     *  converting rows there would silently never be seen. */
    private int migrateFindingFields() {
        int converted = 0;
        for (Finding f : findingRepo.findAll()) {
            Map<String, String> fields = FindingFields.read(f);
            boolean changed = false;
            for (var entry : fields.entrySet()) {
                if (looksLikeHtml(entry.getValue())) {
                    entry.setValue(toMarkdown(entry.getValue()));
                    changed = true;
                }
            }
            if (changed) {
                FindingFields.write(f, fields);
                findingRepo.save(f);
                converted++;
            }
        }
        if (converted > 0) log.info("Migrated fields on {} Finding(s) from HTML to Markdown", converted);
        return converted;
    }

    private int migrateAffections() {
        List<Affection> toConvert = affectionRepo.findAll().stream()
            .filter(a -> looksLikeHtml(a.getDescription()))
            .toList();
        if (toConvert.isEmpty()) return 0;
        toConvert.forEach(a -> a.setDescription(toMarkdown(a.getDescription())));
        affectionRepo.saveAll(toConvert);
        log.info("Migrated {} Affection description(s) from HTML to Markdown", toConvert.size());
        return toConvert.size();
    }

    private int migrateFindingTemplateFields() {
        List<FindingTemplateField> toConvert = findingTemplateFieldRepo.findAll().stream()
            .filter(f -> looksLikeHtml(f.getFieldText()))
            .toList();
        if (toConvert.isEmpty()) return 0;
        toConvert.forEach(f -> f.setFieldText(toMarkdown(f.getFieldText())));
        findingTemplateFieldRepo.saveAll(toConvert);
        log.info("Migrated {} FindingTemplateField(s) from HTML to Markdown", toConvert.size());
        return toConvert.size();
    }

    private int migrateTestingProcedures() {
        List<TestingProcedure> toConvert = testingProcedureRepo.findAll().stream()
            .filter(p -> looksLikeHtml(p.getContent()))
            .toList();
        if (toConvert.isEmpty()) return 0;
        toConvert.forEach(p -> p.setContent(toMarkdown(p.getContent())));
        testingProcedureRepo.saveAll(toConvert);
        log.info("Migrated {} TestingProcedure(s) from HTML to Markdown", toConvert.size());
        return toConvert.size();
    }

    // The frontend's HTML->Markdown conversion (Turndown) backslash-escapes every
    // literal backtick in the source HTML, including ones a user typed intentionally
    // to signal inline code (the editor's toolbar has no inline-code button, so typing
    // a backtick pair was the only way to ask for it). That escaping is correct as
    // generic HTML->MD behavior but defeats the user's intent, and it's already baked
    // into rows converted before the frontend's matching fix (turndown.escape override
    // in ares-ui/src/utils/markdown.ts). Un-escape it here so those rows render as real
    // inline code. Scoped to Finding/Affection/Template only (not TestingProcedure) —
    // narrower than migrateLegacyHtmlToMarkdown() by design, see [[markdown_rich_text_migration]].
    private static final Pattern ESCAPED_BACKTICK = Pattern.compile("\\\\`");

    /** Counts of rows unescaped in one run, per entity. */
    public record BacktickUnescapeResult(int findingFields, int affections, int findingTemplateFields) {
        public int total() { return findingFields + affections + findingTemplateFields; }
    }

    @Transactional
    public BacktickUnescapeResult unescapeBackticks() {
        return new BacktickUnescapeResult(
            unescapeFindingFieldBackticks(),
            unescapeAffectionBackticks(),
            unescapeFindingTemplateFieldBackticks()
        );
    }

    private static boolean hasEscapedBacktick(String text) {
        return text != null && ESCAPED_BACKTICK.matcher(text).find();
    }

    private static String unescapeBackticks(String text) {
        return ESCAPED_BACKTICK.matcher(text).replaceAll("`");
    }

    private int unescapeFindingFieldBackticks() {
        int converted = 0;
        for (Finding f : findingRepo.findAll()) {
            Map<String, String> fields = FindingFields.read(f);
            boolean changed = false;
            for (var entry : fields.entrySet()) {
                if (hasEscapedBacktick(entry.getValue())) {
                    entry.setValue(unescapeBackticks(entry.getValue()));
                    changed = true;
                }
            }
            if (changed) {
                FindingFields.write(f, fields);
                findingRepo.save(f);
                converted++;
            }
        }
        if (converted > 0) log.info("Unescaped backticks in fields on {} Finding(s)", converted);
        return converted;
    }

    private int unescapeAffectionBackticks() {
        List<Affection> toConvert = affectionRepo.findAll().stream()
            .filter(a -> hasEscapedBacktick(a.getDescription()))
            .toList();
        if (toConvert.isEmpty()) return 0;
        toConvert.forEach(a -> a.setDescription(unescapeBackticks(a.getDescription())));
        affectionRepo.saveAll(toConvert);
        log.info("Unescaped backticks in {} Affection description(s)", toConvert.size());
        return toConvert.size();
    }

    private int unescapeFindingTemplateFieldBackticks() {
        List<FindingTemplateField> toConvert = findingTemplateFieldRepo.findAll().stream()
            .filter(f -> hasEscapedBacktick(f.getFieldText()))
            .toList();
        if (toConvert.isEmpty()) return 0;
        toConvert.forEach(f -> f.setFieldText(unescapeBackticks(f.getFieldText())));
        findingTemplateFieldRepo.saveAll(toConvert);
        log.info("Unescaped backticks in {} FindingTemplateField(s)", toConvert.size());
        return toConvert.size();
    }
}
