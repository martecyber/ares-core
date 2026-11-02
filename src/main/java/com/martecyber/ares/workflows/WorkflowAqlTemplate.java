package com.martecyber.ares.workflows;

import com.martecyber.ares.integrations.notifications.MessagingTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow {{...}} placeholder rendering for AQL strings. Same as {@link MessagingTemplate#render},
 * except AQL's own context variables ({{now}}, {{current_iteration_start}},
 * {{current_iteration_end}}, {{p0_sla_period}}..{{p4_sla_period}}) are kept as written instead of
 * being blanked as unknown workflow variables, so AqlVariableExpander can resolve them afterwards.
 */
final class WorkflowAqlTemplate {

    private WorkflowAqlTemplate() {}

    private static final Pattern AQL_VARIABLE =
        Pattern.compile("\\{\\{\\s*(now|current_iteration_start|current_iteration_end|p[0-4]_sla_period)\\s*}}");
    // Placeholders MessagingTemplate leaves alone (no braces).
    private static final char OPEN = '\u0001', CLOSE = '\u0002';
    private static final Pattern PROTECTED = Pattern.compile(OPEN + "([a-z0-9_]+)" + CLOSE);

    static String render(String aql, Map<String, Object> vars) {
        if (aql == null || aql.isEmpty()) return "";
        String guarded = AQL_VARIABLE.matcher(aql).replaceAll(m -> OPEN + m.group(1) + CLOSE);
        String rendered = MessagingTemplate.render(guarded, vars);
        return PROTECTED.matcher(rendered).replaceAll(m -> Matcher.quoteReplacement("{{" + m.group(1) + "}}"));
    }

    /** First {@code trigger.<entity>.<key>} number in the run context (the triggering entity's scope). */
    static Long triggerScopeId(Map<String, Object> vars, String key) {
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("trigger.") && k.endsWith("." + key) && e.getValue() instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }
}
