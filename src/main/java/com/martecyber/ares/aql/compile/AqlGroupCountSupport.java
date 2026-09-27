package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.PostgresColumnField;
import com.martecyber.ares.aql.registry.StatusTransitionAqlField;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grouped-count execution for the dashboard AQL_CHART widget — {@code SELECT groupByField,
 * [seriesField,] COUNT(*) ... WHERE <spec> GROUP BY groupByField[, seriesField]}, reusing
 * whatever {@link Specification} a caller has already built (typically {@code
 * compile(aql).and(scopeSpecification(...))}, the same composition {@code countByAql} already
 * uses in each entity service) rather than recompiling the AQL itself. Groupable fields are
 * either a plain JPA {@link jakarta.persistence.criteria.Path} ({@link PostgresColumnField} —
 * PHYSICAL_COLUMN/VIRTUAL) or a {@link StatusTransitionAqlField}'s correlated scalar subquery
 * ({@code status.<name>}, dashboards remodel Phase 13) — grouping by a RELATION/JSONB path would
 * need a join or an expression this doesn't build; that's out of scope (see the dashboards
 * remodel plan).
 *
 * <p>{@code dateBucket} (nullable — "day"/"week"/"month"/"quarter"/"year") wraps the group
 * expression in Postgres's {@code date_trunc}, letting a DATE-typed field (a plain column like
 * {@code createdAt}, or a {@code status.<name>} transition date) power an "evolution over time"
 * bar chart instead of one bucket per exact timestamp. Only legal when the field is DATE-typed.
 * ({@code "iteration"} is a distinct, entity-specific bucketing mode handled entirely by the
 * caller before reaching here — see {@code FindingService}/{@code DetectionService}
 * {@code countGroupedByAql} — not a value this class ever sees.)
 *
 * <p>{@code seriesField} (nullable) adds a second grouping dimension — the Excel-chart-style
 * "series by" a caller can layer on top of the primary bucket, producing one {@link GroupCount}
 * row per (bucket, series) pair instead of one per bucket. {@code topN}/{@code sortMode} then
 * operate on the primary bucket only (a series is never itself limited or reordered — see
 * {@link #applyTopNAndSort}).
 */
public final class AqlGroupCountSupport {

    private AqlGroupCountSupport() {}

    /** {@code series} is null when no {@code seriesField} was requested — a single implicit
     *  series, matching every pre-existing caller's expectations exactly. */
    public record GroupCount(String label, String series, long count) {}

    /** Legal {@code date_trunc} field arguments this widget surface exposes — deliberately a
     *  small fixed set (not passing the caller's string straight through) even though {@code
     *  cb.literal} already binds it as a query parameter (no SQL-injection risk either way): a
     *  typo'd granularity should fail loud with a clear message, not silently hit Postgres's own
     *  "invalid_parameter_value" error two layers down. */
    private static final Set<String> DATE_BUCKETS = Set.of("day", "week", "month", "quarter", "year");

    /** Legal {@code sortMode} values. Null means "smart default": chronological ascending for a
     *  date-bucketed chart (a time series reads left-to-right through time), count-descending
     *  otherwise (a category breakdown reads most-to-least common first) — exactly today's
     *  pre-existing (pre-sortMode) behavior. */
    private static final Set<String> SORT_MODES = Set.of("value_desc", "value_asc", "label_asc", "label_desc");

    public static final String OTHER_LABEL = "(Other)";

    public static <T> List<GroupCount> execute(EntityManager em, Specification<T> spec, Class<T> entityClass,
                                                EntityAqlRegistry<T> registry, String groupByField) {
        return execute(em, spec, entityClass, registry, groupByField, null, null, null, null);
    }

    public static <T> List<GroupCount> execute(EntityManager em, Specification<T> spec, Class<T> entityClass,
                                                EntityAqlRegistry<T> registry, String groupByField, String dateBucket) {
        return execute(em, spec, entityClass, registry, groupByField, dateBucket, null, null, null);
    }

    public static <T> List<GroupCount> execute(EntityManager em, Specification<T> spec, Class<T> entityClass,
                                                EntityAqlRegistry<T> registry, String groupByField, String dateBucket,
                                                String seriesField, Integer topN, String sortMode) {
        return execute(em, spec, entityClass, registry, groupByField, dateBucket, seriesField, topN, sortMode, dateBucket != null);
    }

    /** Full form — {@code chronological} controls ORDER BY direction and how {@code topN} is
     *  applied (see {@link #limit}), independently of {@code dateBucket} (which only controls
     *  whether a {@code date_trunc} SQL wrapper is applied). Every other overload above defaults
     *  it to {@code dateBucket != null}; the one caller that needs to override it is Finding's
     *  iteration bucketing ({@code FindingService#countGroupedByAql}) — {@code iterationLabel} is
     *  a plain groupable STRING column whose label format already sorts chronologically as a
     *  string (see {@code MonitorIterationHelper}), so {@code date_trunc} must NOT be applied (it
     *  would fail the DATE-type check below), but the result still needs to be treated as
     *  chronological for ordering and for {@code topN} to mean "last N periods" rather than
     *  Excel's "top N, rest as Other" (see {@link #limit}). */
    public static <T> List<GroupCount> execute(EntityManager em, Specification<T> spec, Class<T> entityClass,
                                                EntityAqlRegistry<T> registry, String groupByField, String dateBucket,
                                                String seriesField, Integer topN, String sortMode, boolean chronological) {
        AqlField<T> field = registry.field(groupByField)
            .orElseThrow(() -> new AqlCompileException("Unknown field '" + groupByField + "' for " + registry.entityName()));

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<T> root = cq.from(entityClass);

        Expression<?> groupExpr = resolveGroupExpression(field, root, cq, cb);
        if (dateBucket != null) {
            if (field.type() != AqlFieldType.DATE) {
                throw new AqlCompileException(
                    "'" + groupByField + "' is not a date field — date bucketing only applies to DATE-typed fields");
            }
            if (!DATE_BUCKETS.contains(dateBucket)) {
                throw new AqlCompileException(
                    "Unknown date bucket '" + dateBucket + "' — expected one of " + String.join(", ", DATE_BUCKETS));
            }
            groupExpr = cb.function("date_trunc", OffsetDateTime.class, cb.literal(dateBucket), groupExpr);
        }

        Expression<?> seriesExpr = null;
        if (seriesField != null && !seriesField.isBlank()) {
            AqlField<T> sField = registry.field(seriesField)
                .orElseThrow(() -> new AqlCompileException("Unknown field '" + seriesField + "' for " + registry.entityName()));
            seriesExpr = resolveGroupExpression(sField, root, cq, cb);
        }

        Predicate predicate = spec == null ? null : spec.toPredicate(root, cq, cb);
        if (seriesExpr != null) {
            cq.multiselect(groupExpr, seriesExpr, cb.count(root));
            cq.groupBy(groupExpr, seriesExpr);
        } else {
            cq.multiselect(groupExpr, cb.count(root));
            cq.groupBy(groupExpr);
        }
        if (predicate != null) cq.where(predicate);
        // Chronological order for time-series buckets (a bar chart reading left-to-right through
        // time), the original count-desc order everywhere else — final sortMode (incl. topN
        // "(Other)" folding) is re-applied in Java below since it needs the fully-materialized,
        // series-summed totals per bucket rather than a single SQL ORDER BY.
        cq.orderBy(chronological ? cb.asc(groupExpr) : cb.desc(cb.count(root)));

        List<Tuple> rows = em.createQuery(cq).getResultList();
        List<GroupCount> raw = new ArrayList<>();
        for (Tuple t : rows) {
            String label = t.get(0) == null ? "(none)" : String.valueOf(t.get(0));
            String series = seriesExpr == null ? null : (t.get(1) == null ? "(none)" : String.valueOf(t.get(1)));
            long count = t.get(seriesExpr == null ? 1 : 2, Long.class);
            raw.add(new GroupCount(label, series, count));
        }

        return applyTopNAndSort(raw, topN, sortMode, chronological);
    }

    /** Public entry point for callers that build their own grouped rows outside a single-root
     *  {@link #execute} query — currently only {@code DetectionService}'s iteration-bucket join
     *  against {@code DetectionIterationStat} (Detection has no iteration column of its own to
     *  group by directly, see that call site). Applies the exact same limiting + sort rules as
     *  every other AQL_CHART grouped-count path. {@code bucketed} picks the same smart default
     *  {@link #resolveSortMode} would (chronological for a time/iteration axis, count-desc for a
     *  plain category breakdown) when {@code sortMode} is null, and — critically — picks how
     *  {@code topN} itself is applied (see {@link #limit}). */
    public static List<GroupCount> applyTopNAndSort(List<GroupCount> raw, Integer topN, String sortMode, boolean bucketed) {
        List<GroupCount> limited = limit(raw, topN, bucketed);
        return sortResult(limited, resolveSortMode(sortMode, bucketed));
    }

    /** {@code topN} means two different things depending on whether the chart has a time/
     *  iteration axis, and conflating them was a real bug: for a plain category breakdown
     *  (priority, status, ...) "limit" means Excel's "top N, rest folded into (Other)" — but for
     *  a bucketed chart (month/quarter/iteration/...) there IS no meaningful "rest" to fold; the
     *  buckets are a single chronological sequence, and what an operator actually wants from
     *  "limit to N" is "show only the last N periods" (e.g. the last 6 months, the last 8
     *  iterations), trimming the OLDER end rather than aggregating a synthetic catch-all bucket
     *  that would misread as one more data point in the trend. */
    private static List<GroupCount> limit(List<GroupCount> raw, Integer topN, boolean bucketed) {
        return bucketed ? limitToLastNBuckets(raw, topN) : foldToTopN(raw, topN);
    }

    /** {@code raw} arrives in the query's own chronological order (bucketed queries are always
     *  {@code ORDER BY <bucket> ASC} — see {@link #execute} and {@code DetectionService}'s
     *  iteration join) so each label's first appearance already reflects period order; keeping
     *  only the last {@code topN} distinct labels is exactly "the most recent N periods", no
     *  re-derivation of chronology needed. {@code topN} null/&lt;=0 means no limit. */
    private static List<GroupCount> limitToLastNBuckets(List<GroupCount> raw, Integer topN) {
        if (topN == null || topN <= 0) return raw;
        List<String> orderedLabels = new ArrayList<>();
        for (GroupCount gc : raw) if (!orderedLabels.contains(gc.label())) orderedLabels.add(gc.label());
        if (orderedLabels.size() <= topN) return raw;
        Set<String> kept = Set.copyOf(orderedLabels.subList(orderedLabels.size() - topN, orderedLabels.size()));
        return raw.stream().filter(gc -> kept.contains(gc.label())).toList();
    }

    private static String resolveSortMode(String sortMode, boolean bucketed) {
        if (sortMode != null && !sortMode.isBlank()) {
            if (!SORT_MODES.contains(sortMode)) {
                throw new AqlCompileException("Unknown sort mode '" + sortMode + "' — expected one of " + String.join(", ", SORT_MODES));
            }
            return sortMode;
        }
        return bucketed ? "label_asc" : "value_desc";
    }

    /** Folds every bucket beyond the top {@code topN} (ranked by total count summed across
     *  series) into a single synthetic {@value #OTHER_LABEL} bucket — the Excel "top N, rest as
     *  Other" behavior, for a plain (non-bucketed) category breakdown. {@code topN} null/&lt;=0
     *  means "no limit" — skip folding entirely. */
    private static List<GroupCount> foldToTopN(List<GroupCount> raw, Integer topN) {
        // Preserves first-seen bucket order (count-desc from the SQL query) so a tie below falls
        // back to something stable rather than hash-order.
        Map<String, Long> totalsByLabel = new LinkedHashMap<>();
        for (GroupCount gc : raw) totalsByLabel.merge(gc.label(), gc.count(), Long::sum);

        if (topN == null || topN <= 0 || totalsByLabel.size() <= topN) return raw;

        Set<String> keptSet = Set.copyOf(totalsByLabel.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .toList());

        List<GroupCount> result = new ArrayList<>();
        Map<String, Long> otherBySeries = new LinkedHashMap<>();
        for (GroupCount gc : raw) {
            if (keptSet.contains(gc.label())) {
                result.add(gc);
            } else {
                otherBySeries.merge(gc.series(), gc.count(), Long::sum);
            }
        }
        otherBySeries.forEach((series, count) -> result.add(new GroupCount(OTHER_LABEL, series, count)));
        return result;
    }

    /** Orders the (already limited/folded) result per {@code sortMode} — "(Other)" (only ever
     *  present after {@link #foldToTopN}) always sorts last regardless of {@code sortMode},
     *  matching how Excel/Power BI pin a synthetic "everything else" bucket at the end instead of
     *  letting it jump around. */
    private static List<GroupCount> sortResult(List<GroupCount> result, String sortMode) {
        Map<String, Long> finalTotals = new LinkedHashMap<>();
        for (GroupCount gc : result) finalTotals.merge(gc.label(), gc.count(), Long::sum);

        java.util.Comparator<String> labelOrder = switch (sortMode) {
            case "value_asc" -> java.util.Comparator.comparingLong(finalTotals::get);
            case "value_desc" -> java.util.Comparator.comparingLong((String l) -> finalTotals.get(l)).reversed();
            case "label_asc" -> java.util.Comparator.naturalOrder();
            case "label_desc" -> java.util.Comparator.<String>naturalOrder().reversed();
            default -> java.util.Comparator.comparingLong((String l) -> finalTotals.get(l)).reversed();
        };
        java.util.Comparator<String> withOtherLast = (a, b) -> {
            boolean aOther = a.equals(OTHER_LABEL), bOther = b.equals(OTHER_LABEL);
            if (aOther != bOther) return aOther ? 1 : -1;
            return labelOrder.compare(a, b);
        };
        List<String> orderedLabels = new ArrayList<>(finalTotals.keySet());
        orderedLabels.sort(withOtherLast);

        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < orderedLabels.size(); i++) rank.put(orderedLabels.get(i), i);
        result.sort(java.util.Comparator.comparingInt(gc -> rank.get(gc.label())));
        return result;
    }

    private static <T> Expression<?> resolveGroupExpression(AqlField<T> field, Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        if (field instanceof PostgresColumnField<T> columnField) {
            return columnField.resolvePath(root);
        }
        if (field instanceof StatusTransitionAqlField<T> statusField) {
            return statusField.resolveExpression(root, query, cb);
        }
        throw new AqlCompileException("'" + field.name() + "' can't be used to group by — pick a plain field, not a relation");
    }
}
