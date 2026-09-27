package com.martecyber.ares.aql.compile;

import com.martecyber.ares.common.PriorityThresholds;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.organizations.OrganizationService;
import com.martecyber.ares.organizations.dto.OrganizationDto;
import com.martecyber.ares.projects.MonitorIterationHelper;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves AQL's {@code {{variable}}} context variables (AQL context-variables initiative) — same
 * {@code {{name}}} surface syntax as Workflow's own placeholders (see {@code MessagingTemplate}),
 * but resolved here as a dedicated pre-parse text-expansion pass over the raw AQL string, driven
 * by the query's own org/project scope rather than a workflow run's context map. Called once per
 * AQL execution (every {@code countByAql}/{@code listByAql}/{@code countGroupedByAql}/{@code
 * validate} call site), immediately before {@link com.martecyber.ares.aql.parser.AqlParser#parse}
 * — so {@code {{now}}} re-evaluates fresh on every execution exactly like the pre-existing bare
 * {@code now} literal does, never frozen at query-save time.
 *
 * <p>Two variable kinds:
 * <ul>
 *   <li><b>Date anchors</b> ({@code now}, {@code current_iteration_start}, {@code
 *   current_iteration_end}) — a point in time, usable bare or with a trailing {@code ±N<unit>}
 *   offset (units {@code s/m/h/d/w/M/y} exactly like plain {@code now-7d} already supports, plus a
 *   new {@code i} unit — shift by N whole iterations, only valid for the two iteration anchors).
 *   <li><b>Durations</b> ({@code p0_sla_period}..{@code p4_sla_period}, one per priority level,
 *   each a plain count of days) — not standalone dates, only ever legal as the amount half of an
 *   anchor's {@code ±} offset, e.g. {@code {{now}}-{{p0_sla_period}}} ("more than P0's SLA period
 *   ago"). Pre-unitized (days), so no trailing unit letter is written or expected.
 * </ul>
 *
 * <p>Deliberately implemented as raw-text substitution rather than lexer/parser/compiler changes:
 * this keeps the AQL grammar itself completely unaware of variables (every existing call site that
 * parses/compiles AQL keeps working unchanged), at the cost of the substituted date value needing
 * to be embeddable as a plain unquoted WORD token — see {@link AqlDateLiterals}'s epoch-millis
 * branch, which exists specifically to make that possible without touching the lexer's character
 * set (a colon-bearing {@code OffsetDateTime.toString()} can't lex as one WORD token today).
 *
 * <p>Not wired into Workflow's own {@code {{...}}} paths (WorkflowRunService/WorkflowGraphValidator)
 * — those already run their own {@code MessagingTemplate.render} pass over the AQL string first,
 * which would blank out an unrecognized {@code {{now}}} to {@code ""} before this class ever saw
 * it. A workflow author who needs "now" today still has the plain unbraced {@code now-7d} literal,
 * unaffected either way. Reconciling the two {@code {{...}}} systems is a follow-up, not required
 * for the dashboards/list-view use case this was built for.
 */
@Component
public class AqlVariableExpander {

    private final ProjectRepository projectRepo;
    private final OrganizationRepository organizationRepo;

    public AqlVariableExpander(ProjectRepository projectRepo, OrganizationRepository organizationRepo) {
        this.projectRepo = projectRepo;
        this.organizationRepo = organizationRepo;
    }

    public static final Set<String> ANCHOR_NAMES = Set.of("now", "current_iteration_start", "current_iteration_end");
    private static final Set<String> ITERATION_ANCHOR_NAMES = Set.of("current_iteration_start", "current_iteration_end");

    /** {@code -{{p0_sla_period}}} / {@code +{{p3_sla_period}}} — a duration variable used as the
     *  amount half of an anchor's offset. Only matched immediately after a {@code +}/{@code -}, so
     *  a duration variable used standalone (nonsensical — it's not a date) is deliberately left
     *  untouched here and falls through to {@link #expandAnchors}'s "unknown variable" error. */
    private static final Pattern DURATION_AMOUNT = Pattern.compile("([+-])\\{\\{\\s*(p[0-4]_sla_period)\\s*}}");

    /** {@code {{name}}} optionally followed by a {@code ±N<unit>} offset. */
    private static final Pattern ANCHOR_EXPR =
        Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*}}(?:([+-])(\\d+)([a-zA-Z]))?");

    public String expand(String aql, Long projectId, Long organizationId) {
        if (aql == null || aql.indexOf("{{") < 0) return aql;
        Long resolvedOrgId = resolveOrgId(projectId, organizationId);
        String pass1 = expandDurationAmounts(aql, resolvedOrgId);
        return expandAnchors(pass1, projectId, resolvedOrgId);
    }

    private Long resolveOrgId(Long projectId, Long organizationId) {
        if (organizationId != null) return organizationId;
        if (projectId == null) return null;
        return projectRepo.findOrganizationIdById(projectId).orElse(null);
    }

    private String expandDurationAmounts(String aql, Long organizationId) {
        Matcher m = DURATION_AMOUNT.matcher(aql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int days = slaDaysFor(m.group(2), organizationId);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + days + "d"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private int slaDaysFor(String varName, Long organizationId) {
        if (organizationId == null) {
            throw new AqlCompileException(
                "'{{" + varName + "}}' needs an organization or project in scope to resolve — this query has neither");
        }
        Organization org = organizationRepo.findById(organizationId)
            .orElseThrow(() -> new AqlCompileException("'{{" + varName + "}}' could not resolve — organization not found"));
        OrganizationDto.SlaSettings sla = OrganizationService.parseSla(org.getSettings());
        int priority = Integer.parseInt(varName.substring(1, 2));
        String severity = PriorityThresholds.severityForPriority(priority);
        return sla.forSeverity(severity);
    }

    private String expandAnchors(String aql, Long projectId, Long organizationId) {
        Matcher m = ANCHOR_EXPR.matcher(aql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            OffsetDateTime resolved = resolveAnchorExpression(name, m.group(2), m.group(3), m.group(4), projectId);
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(resolved.toInstant().toEpochMilli())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private OffsetDateTime resolveAnchorExpression(String name, String sign, String amountStr, String unitStr, Long projectId) {
        if (sign == null) {
            return resolveAnchorBase(name, LocalDate.now(), projectId);
        }
        long amount = Long.parseLong(amountStr);
        if (sign.equals("-")) amount = -amount;
        char unit = unitStr.charAt(0);

        if (unit == 'i' || unit == 'I') {
            if (!ITERATION_ANCHOR_NAMES.contains(name)) {
                throw new AqlCompileException(
                    "'i' (iterations) is only valid for current_iteration_start/current_iteration_end, not '{{" + name + "}}'");
            }
            Project project = requireMonitorProject(name, projectId);
            LocalDate shiftedRef = MonitorIterationHelper.shiftReferenceDate(project.getIterationCadence(), LocalDate.now(), amount);
            return resolveAnchorBase(name, shiftedRef, projectId);
        }

        if (name.equals("now")) {
            return AqlDateLiterals.applyOffset(OffsetDateTime.now(), amount, unit, "{{now}}");
        }
        // Iteration anchors + a time-unit offset (not iterations): offset from the iteration's own
        // boundary, e.g. {{current_iteration_end}}-1d = the iteration's last day.
        return AqlDateLiterals.applyOffset(resolveAnchorBase(name, LocalDate.now(), projectId), amount, unit,
            "{{" + name + "}}");
    }

    private OffsetDateTime resolveAnchorBase(String name, LocalDate referenceDate, Long projectId) {
        if (name.equals("now")) {
            return OffsetDateTime.now();
        }
        if (!ITERATION_ANCHOR_NAMES.contains(name)) {
            throw new AqlCompileException("Unknown AQL variable '{{" + name + "}}'");
        }
        Project project = requireMonitorProject(name, projectId);
        LocalDate[] bounds = MonitorIterationHelper.iterationBounds(project.getIterationCadence(), referenceDate);
        LocalDate chosen = name.equals("current_iteration_start") ? bounds[0] : bounds[1];
        return chosen.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    private Project requireMonitorProject(String varName, Long projectId) {
        if (projectId == null) {
            throw new AqlCompileException("'{{" + varName + "}}' is only available in a project-scoped query");
        }
        Project project = projectRepo.findById(projectId)
            .orElseThrow(() -> new AqlCompileException("'{{" + varName + "}}' could not resolve — project not found"));
        if (project.getIterationCadence() == null) {
            throw new AqlCompileException(
                "'{{" + varName + "}}' is only available in monitoring projects with an iteration cadence set");
        }
        return project;
    }
}
