package com.martecyber.ares.dashboards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.compile.AqlGroupCountSupport;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every widget query the dashboard editor can produce against real Postgres: the default
 * seed widgets, a filter on every AQL field of finding/asset/detection, every "Group by" option
 * (with each date bucket), every list sort key, and the {{now}} variable / null checks. Collects all failures and reports them
 * together, so a broken field shows up here instead of as an empty widget in the browser.
 * Uses org 1 / project 2 from the dev seed data (same convention as the other dashboard ITs);
 * -DSWEEP_ORG_ID / -DSWEEP_PROJECT_ID point it at a copy of a real database instead.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class DashboardWidgetAqlSweepIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        int port = Integer.getInteger("AQL_IT_PG_PORT", 15432);
        String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/ares";
        registry.add("spring.datasource.url", () -> jdbcUrl + "?currentSchema=ares");
        registry.add("spring.datasource.username", () -> "ares");
        registry.add("spring.datasource.password", () -> "ares");
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> "ares");
        registry.add("spring.flyway.password", () -> "ares");
    }

    private static final long ORG_ID = Long.getLong("SWEEP_ORG_ID", 1L);
    private static final long PROJECT_ID = Long.getLong("SWEEP_PROJECT_ID", 2L);

    /** Mirrors DashboardService.ALLOWED_AQL_ENTITIES. */
    private static final Map<DashboardLevel, List<String>> ENTITIES = Map.of(
        DashboardLevel.ORGANIZATION, List.of("finding", "asset"),
        DashboardLevel.PROJECT, List.of("finding", "asset", "detection"));

    /** Mirrors the UI's groupableFieldOptions filter and DASHBOARD_LIST_SORT_FIELDS_BY_ENTITY. */
    private static final Set<AqlFieldType> GROUPABLE = Set.of(
        AqlFieldType.STRING, AqlFieldType.BOOLEAN, AqlFieldType.ENUM, AqlFieldType.PRIORITY, AqlFieldType.DATE);
    private static final Map<String, List<String>> SORT_FIELDS = Map.of(
        "finding", List.of("createdAt", "reportedAt", "updatedAt", "dueDate", "slaDeadline", "priority", "title"),
        "asset", List.of("createdAt", "identifier", "type", "code"),
        "detection", List.of("createdAt", "lastSeen", "priority", "status", "title", "sourceType"));

    @Autowired private DashboardWidgetDataService dataService;
    @Autowired private DashboardService dashboardService;
    @Autowired private AqlRegistryLookup registries;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> failures = new ArrayList<>();
    private int runs;

    private void authenticateAsPlatformAdmin() {
        var auth = new TestingAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void everyEditorWidgetQueryRuns() {
        authenticateAsPlatformAdmin();

        // Default seed widgets, through the real seeding path.
        for (DashboardLevel level : List.of(DashboardLevel.ORGANIZATION, DashboardLevel.PROJECT)) {
            long scope = level == DashboardLevel.PROJECT ? PROJECT_ID : ORG_ID;
            var dashboard = dashboardService.get(dashboardService.list(level, scope).get(0).id());
            for (var w : dashboard.widgets()) {
                if (!w.type().name().startsWith("AQL_")) continue;
                run(level, w.type(), w.config(), "seed '" + w.title() + "'");
            }
        }

        for (var e : ENTITIES.entrySet()) {
            DashboardLevel level = e.getKey();
            for (String entity : e.getValue()) {
                List<? extends AqlField<?>> fields = registries.require(entity).allFields();

                for (AqlField<?> f : fields) {
                    // scope.* needs a project; at organization level it's a clear error by design.
                    if (level == DashboardLevel.ORGANIZATION && f.name().contains("scope.")) continue;
                    String filter = sampleFilter(f);
                    if (filter != null) run(level, DashboardWidgetType.AQL_COUNT, config(entity, filter), "filter " + filter);
                }
                for (AqlField<?> f : fields) {
                    if (!GROUPABLE.contains(f.type()) || !AqlGroupCountSupport.isGroupable(f)) continue;
                    ObjectNode c = config(entity, "");
                    c.put("groupByField", f.name());
                    drillDown(level, entity, f.name(), null, run(level, DashboardWidgetType.AQL_CHART, c, "groupBy " + f.name()));
                    if (f.type() == AqlFieldType.DATE) {
                        for (String bucket : List.of("day", "week", "month", "quarter", "year")) {
                            ObjectNode b = c.deepCopy();
                            b.put("dateBucket", bucket);
                            drillDown(level, entity, f.name(), bucket,
                                run(level, DashboardWidgetType.AQL_CHART, b, "groupBy " + f.name() + " by " + bucket));
                        }
                    }
                }
                if (!entity.equals("asset")) {
                    ObjectNode c = config(entity, "");
                    c.put("dateBucket", "iteration");
                    if (level == DashboardLevel.PROJECT || entity.equals("finding")) {
                        run(level, DashboardWidgetType.AQL_CHART, c, "bucket by iteration");
                    }
                }
                for (String sort : SORT_FIELDS.get(entity)) {
                    for (String dir : List.of("asc", "desc")) {
                        ObjectNode c = config(entity, "");
                        c.put("sortField", sort);
                        c.put("sortDir", dir);
                        c.put("limit", 5);
                        run(level, DashboardWidgetType.AQL_LIST, c, "sort " + sort + " " + dir);
                    }
                }
                for (String q : List.of("createdAt > {{now}}-30d", "createdAt < {{now}}", "updatedAt != null", "createdAt == null")) {
                    run(level, DashboardWidgetType.AQL_COUNT, config(entity, q), "variable " + q);
                }
            }
        }

        System.out.println("Dashboard widget AQL sweep: " + runs + " queries, " + failures.size() + " failures");
        failures.forEach(System.out::println);
        assertTrue(failures.isEmpty(), failures.size() + " widget queries failed:\n" + String.join("\n", failures));
    }

    private ObjectNode config(String entity, String aql) {
        ObjectNode c = mapper.createObjectNode();
        c.put("entity", entity);
        c.put("aql", aql);
        return c;
    }

    private Object run(DashboardLevel level, DashboardWidgetType type, JsonNode config, String label) {
        runs++;
        DashboardWidget widget = new DashboardWidget();
        widget.setType(type);
        long scope = level == DashboardLevel.PROJECT ? PROJECT_ID : ORG_ID;
        try {
            return dataService.dataFor(widget, config, level, scope);
        } catch (Exception ex) {
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            failures.add(level + " " + config.path("entity").asText() + " " + type + " [" + label + "]: "
                + ex.getClass().getSimpleName() + ": " + firstLine(ex.getMessage())
                + (root != ex ? " <- " + root.getClass().getSimpleName() + ": " + firstLine(root.getMessage()) : ""));
            return null;
        }
    }

    /** Runs the click-through query for (up to) the first few bars of a chart, built the same way
     *  AqlChartWidget.vue's bucketAql + utils/aqlClause.ts build it. */
    private void drillDown(DashboardLevel level, String entity, String field, String bucket, Object data) {
        if (!(data instanceof Map<?, ?> m) || !(m.get("buckets") instanceof List<?> buckets)) return;
        buckets.stream().limit(3).forEach(o -> {
            String label = ((AqlGroupCountSupport.GroupCount) o).label();
            String clause;
            if (label.equals("(none)")) {
                clause = field + " == null";
            } else if (bucket != null) {
                java.time.Instant start = label.length() == 10
                    ? java.time.LocalDate.parse(label).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    : java.time.OffsetDateTime.parse(label).toInstant();
                java.time.Instant end = start.atOffset(java.time.ZoneOffset.UTC).plus(1, switch (bucket) {
                    case "day" -> java.time.temporal.ChronoUnit.DAYS;
                    case "week" -> java.time.temporal.ChronoUnit.WEEKS;
                    case "month" -> java.time.temporal.ChronoUnit.MONTHS;
                    case "quarter" -> java.time.temporal.ChronoUnit.MONTHS; // width doesn't matter here
                    default -> java.time.temporal.ChronoUnit.YEARS;
                }).toInstant();
                clause = field + " >= \"" + start + "\" AND " + field + " < \"" + end + "\"";
            } else {
                String value = field.equals("priority") ? "P" + label : label;
                clause = field + " == \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            run(level, DashboardWidgetType.AQL_COUNT, config(entity, clause), "drill-down " + clause);
        });
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** A query a user could type for this field: its first supported operator with a value of the
     *  right shape. Null when the field takes no direct comparison (a bare relation). */
    private static String sampleFilter(AqlField<?> f) {
        Set<AqlOperator> ops = f.supportedOperators();
        if (ops.isEmpty()) return null;
        String value = switch (f.type()) {
            case NUMBER -> "1";
            case BOOLEAN -> "true";
            case DATE -> "now-30d";
            case PRIORITY -> "P1";
            case ENUM -> f.allowedValues().isEmpty() ? "\"x\"" : "\"" + f.allowedValues().get(0) + "\"";
            default -> "\"x\"";
        };
        AqlOperator op = ops.contains(AqlOperator.EQ) ? AqlOperator.EQ
            : ops.contains(AqlOperator.HAS) ? AqlOperator.HAS
            : ops.contains(AqlOperator.GT) ? AqlOperator.GT
            : ops.contains(AqlOperator.CONTAINS) ? AqlOperator.CONTAINS
            : ops.iterator().next();
        String token = switch (op) {
            case EQ -> "==";
            case NEQ -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case CONTAINS -> "~=";
            case HAS -> "HAS";
            case IN -> "IN";
        };
        if (op == AqlOperator.IN) value = "[" + value + "]";
        return f.name() + " " + token + " " + value;
    }
}
