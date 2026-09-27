package com.martecyber.ares.integrations.notifications;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny {@code {{var}}}-style template renderer for messaging bindings.
 *
 *  • Tolerates inner whitespace: {@code {{ name }}} == {@code {{name}}}.
 *  • Unknown variables collapse to empty string — typos don't blow up delivery.
 *  • Values are inserted verbatim (no HTML/markdown escaping); each sender is
 *    already responsible for transport-specific escaping when it builds its
 *    own payload.
 *  • Variable names may contain dots ({@code {{steps.nodeId.output.field}}}) — added for
 *    Workflows' context-variable templating (see {@code WorkflowContextFlattener}), which passes
 *    a flat map keyed by literal dotted strings rather than actually-nested objects. Backward
 *    compatible: every existing plain {@code {{var}}} template still matches the same way.
 *  • Variable names may also contain hyphens — the workflow editor generates node ids as
 *    {@code <type>-<timestamp>-<seq>} (see WorkflowCanvas.vue), so a step-output reference like
 *    {@code {{steps.action_agent_task-1755300000000-3.output.exitCode}}} must match in full or it
 *    silently fails to substitute (this was a real, reported bug: hyphenated step references were
 *    delivered as unrendered literal text while non-hyphenated trigger/variable references worked).
 *  • {@code {{#name}}...{{/name}}} repeats the enclosed fragment once per element of
 *    {@code vars.get(name)} when it's a {@code List<Map<String,Object>>} (e.g. a finding's
 *    affections/references — see {@code FindingPresentationService}) — Mustache/Handlebars-style,
 *    so the template author controls the HTML of each element instead of receiving one fixed,
 *    pre-formatted blob. A missing or non-list value collapses the whole block to nothing (that's
 *    what makes a section genuinely optional, unlike a plain {@code {{var}}} which just leaves an
 *    empty string in place). Inside the block, bare names ({@code {{code}}}) resolve against the
 *    current element first, falling back to the outer context for anything the element doesn't
 *    have (so {@code {{finding.title}}} still works inside a loop over one of its own sub-lists) —
 *    blocks nest for free since each element is rendered via a recursive call to this same
 *    renderer, which expands its own nested {{#...}} blocks before resolving its own leaves.
 */
public final class MessagingTemplate {

    private MessagingTemplate() {}

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*}}");
    private static final Pattern BLOCK = Pattern.compile("\\{\\{#([A-Za-z][A-Za-z0-9_.-]*)}}(.*?)\\{\\{/\\1}}", Pattern.DOTALL);

    public static String render(String template, Map<String, Object> vars) {
        return render(template, vars, Escaping.NONE);
    }

    /**
     * Same substitution as {@link #render}, but escapes each substituted value's CommonMark
     * special characters first — for templates whose result is itself parsed as CommonMark
     * afterwards (see the Workflows ACTION_NOTIFICATION markdown-editor feature). Without this, a
     * substituted value containing an underscore (a common asset identifier/title character,
     * e.g. {@code my_host_name}) would be silently reinterpreted as emphasis markup by the
     * CommonMark parser — the template author's own markdown (surrounding the placeholder) is
     * untouched since only the replacement text is escaped, not the template.
     */
    public static String renderMarkdownSafe(String template, Map<String, Object> vars) {
        return render(template, vars, Escaping.MARKDOWN);
    }

    /**
     * Same substitution as {@link #render}, but HTML-escapes each substituted value first — for
     * templates whose result is sent as-is as an HTML document (KB EmailTemplate content, see
     * EmailSender). Without this, a substituted value containing {@code &}/{@code <}/{@code >}
     * (a finding/detection title is user-controlled data) could break the template's HTML
     * structure or inject markup — the template author's own HTML (surrounding the placeholder)
     * is untouched since only the replacement text is escaped, not the template.
     */
    public static String renderHtmlSafe(String template, Map<String, Object> vars) {
        return render(template, vars, Escaping.HTML);
    }

    private enum Escaping { NONE, MARKDOWN, HTML }

    private static final Pattern MD_SPECIAL = Pattern.compile("([\\\\`*_{}\\[\\]()#+\\-.!|~])");

    private static String render(String template, Map<String, Object> vars, Escaping escaping) {
        if (template == null || template.isEmpty()) return "";
        template = expandBlocks(template, vars, escaping);
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars == null ? null : vars.get(m.group(1));
            String value = v == null ? "" : v.toString();
            value = switch (escaping) {
                case MARKDOWN -> MD_SPECIAL.matcher(value).replaceAll("\\\\$1");
                case HTML -> escapeHtml(value);
                case NONE -> value;
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static String expandBlocks(String template, Map<String, Object> vars, Escaping escaping) {
        Matcher m = BLOCK.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars == null ? null : vars.get(m.group(1));
            String replacement = "";
            if (v instanceof List<?> list) {
                String inner = m.group(2);
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    Map<String, Object> merged = vars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vars);
                    if (item instanceof Map<?, ?> itemMap) merged.putAll((Map<String, Object>) itemMap);
                    sb.append(render(inner, merged, escaping));
                }
                replacement = sb.toString();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
