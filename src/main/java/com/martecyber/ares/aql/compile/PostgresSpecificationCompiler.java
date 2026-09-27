package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.materialize.KbMaterializedRef;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.parser.AqlValue;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldKind;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.JsonbArrayMatchField;
import com.martecyber.ares.aql.registry.JsonbPathField;
import com.martecyber.ares.aql.registry.KbMaterializedField;
import com.martecyber.ares.aql.registry.PostgresColumnField;
import com.martecyber.ares.aql.registry.RelationAqlField;
import com.martecyber.ares.aql.registry.RelationLeafAqlField;
import com.martecyber.ares.aql.registry.StatusTransitionAqlField;
import com.martecyber.ares.references.ReferenceEntry;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Compiles a parsed {@link AqlNode} into a Spring Data {@link Specification}, driven entirely by
 * an {@link EntityAqlRegistry}. PHYSICAL_COLUMN/VIRTUAL resolve to a plain JPA {@link Path};
 * KB_MATERIALIZED compiles to a correlated EXISTS subquery joining the entity -&gt; ReferenceEntry
 * -&gt; kb_materialized_ref (V145), no Mongo round trip. RELATION compiles to a correlated EXISTS
 * subquery too, but against a general related Postgres entity described by a {@link
 * RelationAqlField} rather than the fixed ReferenceEntry/kb_materialized_ref shape — see
 * {@link #compileRelation}.
 *
 * <p>Internal methods take {@link AbstractQuery} rather than {@link CriteriaQuery} (the top-level
 * {@link #compile} entry point still returns a plain {@link Specification}, whose contract fixes
 * CriteriaQuery) specifically so {@link #compileRelation} can recurse into a fresh compiler scoped
 * to the target entity's own registry, called with a {@link Subquery} — which does NOT extend
 * CriteriaQuery (both extend AbstractQuery), so passing one through the old CriteriaQuery-typed
 * signature wasn't possible.
 */
public class PostgresSpecificationCompiler<T> {

    private final EntityAqlRegistry<T> registry;

    public PostgresSpecificationCompiler(EntityAqlRegistry<T> registry) {
        this.registry = registry;
    }

    public Specification<T> compile(AqlNode node) {
        return (root, query, cb) -> toPredicate(node, root, query, cb);
    }

    private Predicate toPredicate(AqlNode node, Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        return switch (node) {
            case AqlNode.And and -> cb.and(and.operands().stream().map(n -> toPredicate(n, root, query, cb)).toArray(Predicate[]::new));
            case AqlNode.Or or -> cb.or(or.operands().stream().map(n -> toPredicate(n, root, query, cb)).toArray(Predicate[]::new));
            case AqlNode.Not not -> cb.not(toPredicate(not.operand(), root, query, cb));
            case AqlNode.Comparison cmp -> compileComparison(cmp, root, query, cb);
            case AqlNode.BareTerm term -> compileBareTerm(term, root, cb);
        };
    }

    private Predicate compileComparison(AqlNode.Comparison cmp, Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        AqlField<T> field = registry.requireField(cmp.field());
        if (!field.supportedOperators().contains(cmp.operator())) {
            throw new AqlCompileException(
                "Field '" + field.name() + "' does not support operator '" + cmp.operator() + "'");
        }
        if (field.kind() == AqlFieldKind.KB_MATERIALIZED) {
            return compileMaterialized((KbMaterializedField<T>) field, cmp, root, query, cb);
        }
        if (field.kind() == AqlFieldKind.RELATION) {
            // Only ever a RelationLeafAqlField here — the bare RelationAqlField itself always has
            // an empty supportedOperators(), so it's already rejected by the whitelist check above.
            return compileRelation((RelationLeafAqlField<T, ?>) field, cmp, root, query, cb);
        }
        if (field.kind() == AqlFieldKind.JSONB_PATH) {
            return compileJsonbPath((JsonbPathField<T>) field, cmp, root, cb);
        }
        if (field.kind() == AqlFieldKind.JSONB_ARRAY_MATCH) {
            return compileJsonbArrayMatch((JsonbArrayMatchField<T>) field, cmp, root, cb);
        }
        if (field.kind() == AqlFieldKind.STATUS_TRANSITION) {
            return compileStatusTransition((StatusTransitionAqlField<T>) field, cmp, root, query, cb);
        }
        if (!(field instanceof PostgresColumnField<T> pgField)) {
            throw new AqlCompileException("Field '" + field.name() + "' cannot be resolved against Postgres");
        }
        Path<?> path = pgField.resolvePath(root);
        return buildPredicate(path, field.type(), cmp.operator(), cmp.value(), cb);
    }

    /** Correlated {@code EXISTS(SELECT 1 FROM <target/bridge entities> WHERE <correlation> AND
     *  <recursively-compiled leaf predicate>)} — the join shape comes from {@link RelationAqlField
     *  #correlation()} instead of being hardcoded to ReferenceEntry the way {@link
     *  #compileMaterialized} is, and the value predicate is a full recursive compile against the
     *  target registry (via a fresh {@link PostgresSpecificationCompiler} scoped to it), not a
     *  single hardcoded attribute comparison — so further RELATION/JSONB_PATH/etc. chaining on the
     *  target entity works for free. Same "independent per comparison" caveat as {@code
     *  compileMaterialized}: ANDing several {@code relation.leaf} conditions together means "some
     *  related row (possibly a different one for each) satisfies each," not "one single related
     *  row satisfies all of them."
     *
     *  <p>{@code RelationAqlField.listOf} relations (list[X] array-membership, see that factory's
     *  own doc comment) take a completely different path — {@link #compileArrayMembershipRelation}
     *  — instead of the correlated-EXISTS shape below. Measured live against production-scale data:
     *  a correlated EXISTS re-evaluates {@code ares.array_contains_ci} for every row of the OUTER
     *  query for every candidate row of the target table — an opaque function call from the
     *  planner's perspective, so it can never use the GIN indexes already sitting on these array
     *  columns, regardless of cost settings (confirmed with {@code enable_seqscan=off}: the planner
     *  genuinely has no alternative plan for this shape, it isn't a costing problem). A 2-hop query
     *  against 378k CVEs took ~11s; the exact same query nested one level deeper under an exploit
     *  relation (5 rows) took ~18s, since the expensive inner EXISTS gets re-evaluated per exploit
     *  row instead of once. */
    private <R> Predicate compileRelation(RelationLeafAqlField<T, R> leafField, AqlNode.Comparison cmp,
                                           Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        RelationAqlField<T, R> relation = leafField.relation();
        if (relation.arrayAttribute() != null) {
            return compileArrayMembershipRelation(leafField, relation, cmp, root, query, cb);
        }
        // NEQ must negate the WHOLE "does a matching related row exist" result, not the inner
        // per-row comparison — tags.name != "X" means "no tag of this asset is named X", the same
        // thing NOT (tags.name == "X") already compiles to via the top-level Not case above.
        // Pushing the negation into the correlated subquery's own WHERE instead (the bug this
        // fixes) produces EXISTS(tag WHERE tag.name != 'X') — true for any asset with some OTHER
        // tag, even one that ALSO has "X". Forcing EQ inside and negating the EXISTS as a whole
        // gives the two forms genuinely identical semantics, list values included (buildPredicate
        // already treats EQ-with-a-list exactly like IN — see its own doc comment there).
        boolean negateWhole = cmp.operator() == AqlOperator.NEQ;
        AqlOperator innerOp = negateWhole ? AqlOperator.EQ : cmp.operator();

        Subquery<Long> sub = query.subquery(Long.class);
        Root<R> targetRoot = sub.from(relation.targetEntityClass());
        sub.select(targetRoot.get("id"));

        Predicate correlate = relation.correlation().correlate(root, targetRoot, sub, cb);
        PostgresSpecificationCompiler<R> targetCompiler = new PostgresSpecificationCompiler<>(leafField.targetRegistry());
        AqlNode.Comparison innerCmp = new AqlNode.Comparison(leafField.leaf().name(), innerOp, cmp.value());
        Predicate valueMatch = targetCompiler.compileComparison(innerCmp, targetRoot, sub, cb);

        sub.where(cb.and(correlate, valueMatch));
        Predicate exists = cb.exists(sub);
        return negateWhole ? cb.not(exists) : exists;
    }

    /** The list[X] fast path (V159): the target-side leaf predicate never actually depends on the
     *  outer row (it's a pure filter on the target entity — "which CWEs relate to CAPEC-94" doesn't
     *  care which CVE/exploit is asking), so instead of re-checking it per outer row inside a
     *  correlated EXISTS, compute the matching target ids ONCE via an uncorrelated {@code array_agg}
     *  subquery, then test the source array for overlap against that fixed (usually small) set —
     *  {@code ares.array_overlaps_ci(source.arr, candidates)}, V159, applies {@code
     *  ares.lower_array} to the source side using the exact same expression the V159 GIN indexes are
     *  built on, so the planner can push this down to a Bitmap Index Scan instead of a Seq Scan.
     *  {@code candidates} is pre-lowered by the {@code array_agg(lower(...))} below, so no further
     *  normalization is needed on that side. */
    private <R> Predicate compileArrayMembershipRelation(RelationLeafAqlField<T, R> leafField, RelationAqlField<T, R> relation,
                                                           AqlNode.Comparison cmp, Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        // Same NEQ treatment as compileRelation above and for the same reason: negate the whole
        // "does the source array overlap the candidate set" result, not the candidate-selection
        // predicate — otherwise "candidates" becomes "every OTHER target id", and any source array
        // with more than one element overlaps that set regardless of whether it also contains the
        // one being excluded.
        boolean negateWhole = cmp.operator() == AqlOperator.NEQ;
        AqlOperator innerOp = negateWhole ? AqlOperator.EQ : cmp.operator();

        Subquery<String[]> sub = query.subquery(String[].class);
        Root<R> targetRoot = sub.from(relation.targetEntityClass());
        Expression<String> loweredId = cb.lower(targetRoot.get(relation.targetIdAttribute()).as(String.class));
        sub.select(cb.function("array_agg", String[].class, loweredId));

        PostgresSpecificationCompiler<R> targetCompiler = new PostgresSpecificationCompiler<>(leafField.targetRegistry());
        AqlNode.Comparison innerCmp = new AqlNode.Comparison(leafField.leaf().name(), innerOp, cmp.value());
        Predicate valueMatch = targetCompiler.compileComparison(innerCmp, targetRoot, sub, cb);
        sub.where(valueMatch);

        Expression<String[]> sourceArray = root.get(relation.arrayAttribute());
        Predicate overlaps = cb.isTrue(cb.function("ares.array_overlaps_ci", Boolean.class, sourceArray, sub));
        return negateWhole ? cb.not(overlaps) : overlaps;
    }

    /** {@code status.<statusName>} — resolves the field's own correlated {@code MIN(changed_at)}
     *  scalar subquery ({@link StatusTransitionAqlField#resolveExpression}) and hands it straight
     *  to the same {@link #buildPredicate} every plain DATE column goes through — a {@link
     *  jakarta.persistence.criteria.Subquery} implements {@code Expression}, so no separate
     *  comparison logic is needed here. A status the entity never transitioned into makes the
     *  subquery return SQL NULL, which every comparison operator already treats as "doesn't
     *  match" via ordinary NULL semantics — exactly the desired behavior, no extra IS NOT NULL
     *  guard required. */
    private Predicate compileStatusTransition(StatusTransitionAqlField<T> field, AqlNode.Comparison cmp,
                                               Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        Expression<OffsetDateTime> expr = field.resolveExpression(root, query, cb);
        return buildPredicate(expr, field.type(), cmp.operator(), cmp.value(), cb);
    }

    /** jsonb_extract_path_text(&lt;column&gt;, &lt;key&gt;) is the function-call form of the {@code ->>}
     *  operator — usable from JPA Criteria via cb.function without a native query. Replaces the
     *  one prior precedent for jsonb querying in this codebase (AssetService's blind
     *  {@code metadata::text ILIKE '%q%'}) with a key-scoped, typed comparison.
     *
     *  <p>NUMBER/DATE/BOOLEAN route through a dedicated {@code ares.jsonb_path_*} function (V187)
     *  instead of the plain text extraction + {@code buildPredicate}'s own {@code .as(...)} —
     *  {@code jsonb_extract_path_text} always returns SQL text, and {@code Expression.as(Double
     *  .class)} on a {@code cb.function()} result does NOT emit an actual SQL CAST (Hibernate only
     *  retags the Java-side type), so Postgres would otherwise reject the comparison outright
     *  ("operator does not exist: text = double precision"). STRING/ENUM/STRING_LIST need no cast
     *  at all — the extracted text IS already the right SQL type for those. */
    private Predicate compileJsonbPath(JsonbPathField<T> field, AqlNode.Comparison cmp, Root<T> root, CriteriaBuilder cb) {
        Expression<String> jsonbColumn = root.get(field.jsonbColumn());
        Expression<?> extracted = switch (field.type()) {
            case NUMBER -> cb.function("ares.jsonb_path_double", Double.class, jsonbColumn, cb.literal(field.jsonKey()));
            case DATE -> cb.function("ares.jsonb_path_timestamptz", OffsetDateTime.class, jsonbColumn, cb.literal(field.jsonKey()));
            case BOOLEAN -> cb.function("ares.jsonb_path_boolean", Boolean.class, jsonbColumn, cb.literal(field.jsonKey()));
            case STRING, ENUM, STRING_LIST, PRIORITY ->
                cb.function("jsonb_extract_path_text", String.class, jsonbColumn, cb.literal(field.jsonKey()));
        };
        return buildPredicate(extracted, field.type(), cmp.operator(), cmp.value(), cb);
    }

    /** Calls {@code sqlFunction(jsonbColumn, extraArgs..., needle) -&gt; boolean} the same way {@link
     *  #compileJsonbPath} calls {@code jsonb_extract_path_text} — the general-purpose jsonb-ARRAY
     *  escape hatch for match logic a single scalar extraction can't express (element iteration,
     *  multi-sub-field matching within one element, or real value-range resolution — see {@link
     *  JsonbArrayMatchField}'s own doc comment for the case this exists for). EQ/NEQ are the only
     *  legal operators (enforced by the field's own {@code supportedOperators()}, checked before
     *  this is ever reached), so the value is always a bare scalar — no list/range case to handle. */
    private Predicate compileJsonbArrayMatch(JsonbArrayMatchField<T> field, AqlNode.Comparison cmp, Root<T> root, CriteriaBuilder cb) {
        if (!(cmp.value() instanceof AqlValue.Scalar scalar)) {
            throw new AqlCompileException("Field '" + field.name() + "' only supports a single scalar value");
        }
        List<Expression<?>> args = new java.util.ArrayList<>();
        args.add(root.get(field.jsonbColumn()));
        for (String extra : field.extraArgs()) args.add(cb.literal(extra));
        args.add(cb.literal(scalar.raw()));
        Predicate matches = cb.isTrue(cb.function(field.sqlFunction(), Boolean.class, args.toArray(Expression<?>[]::new)));
        return cmp.operator() == AqlOperator.NEQ ? cb.not(matches) : matches;
    }

    /** Correlated EXISTS(SELECT 1 FROM reference_entry re JOIN &lt;references-inverse&gt; d ON ... JOIN
     *  kb_materialized_ref kmr ON kmr.id=(re.catalogId,re.title) WHERE d=root AND re.catalogId=:catalogId
     *  AND kmr.&lt;attr&gt; &lt;op&gt; :value) — independent per comparison, so combining several cve.*
     *  conditions with AND means "some reference (possibly different ones) satisfies each", not
     *  "one single reference satisfies all of them" — simpler and more predictable than trying to
     *  correlate multiple KB conditions to the exact same reference row. */
    private Predicate compileMaterialized(KbMaterializedField<T> field, AqlNode.Comparison cmp,
                                           Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        // Same NEQ treatment as compileRelation, same reason — see its own comment.
        boolean negateWhole = cmp.operator() == AqlOperator.NEQ;
        AqlOperator innerOp = negateWhole ? AqlOperator.EQ : cmp.operator();

        Subquery<Long> sub = query.subquery(Long.class);
        Root<ReferenceEntry> refRoot = sub.from(ReferenceEntry.class);
        Join<ReferenceEntry, T> backJoin = refRoot.join(field.referencesInverseAttribute());
        Root<KbMaterializedRef> kmrRoot = sub.from(KbMaterializedRef.class);
        sub.select(refRoot.get("id"));

        Predicate correlate = cb.equal(backJoin, root);
        Predicate catalogMatch = cb.equal(refRoot.get("catalogId"), field.catalogId());
        Predicate materializedJoin = cb.and(
            cb.equal(kmrRoot.get("id").get("catalogId"), refRoot.get("catalogId")),
            cb.equal(kmrRoot.get("id").get("code"), refRoot.get("title")));
        Predicate valueMatch = buildPredicate(
            kmrRoot.get(field.materializedAttribute()), field.type(), innerOp, cmp.value(), cb);

        sub.where(cb.and(correlate, catalogMatch, materializedJoin, valueMatch));
        Predicate exists = cb.exists(sub);
        return negateWhole ? cb.not(exists) : exists;
    }

    private Predicate compileBareTerm(AqlNode.BareTerm term, Root<T> root, CriteriaBuilder cb) {
        List<AqlField<T>> searchFields = registry.defaultSearchFields();
        if (searchFields.isEmpty()) {
            throw new AqlCompileException("Entity '" + registry.entityName() + "' has no free-text search fields");
        }
        String needle = "%" + term.text().toLowerCase(Locale.ROOT) + "%";
        Predicate[] preds = searchFields.stream()
            .map(f -> {
                if (!(f instanceof PostgresColumnField<T> pgField)) {
                    throw new AqlCompileException("Default search field '" + f.name() + "' cannot be resolved against Postgres");
                }
                return cb.like(cb.lower(pgField.resolvePath(root).as(String.class)), needle);
            })
            .toArray(Predicate[]::new);
        return cb.or(preds);
    }

    private Predicate buildPredicate(Expression<?> path, AqlFieldType type, AqlOperator op, AqlValue value, CriteriaBuilder cb) {
        if (value instanceof AqlValue.ListValue list) {
            // IN behaves exactly like EQ against a list — see AqlOperator's doc comment.
            if (op != AqlOperator.EQ && op != AqlOperator.NEQ && op != AqlOperator.IN) {
                throw new AqlCompileException("Operator '" + op + "' does not accept a list value");
            }
            // Must match equalityPredicate's per-type casting exactly, including cb.lower() for
            // string-ish types — coerceScalar() already lowercases the list's Java-side values,
            // but without also lowering the column expression here, a case-insensitive match
            // against real (often not-all-lowercase, e.g. CVE's "CRITICAL"/"HIGH") stored data
            // would silently return zero rows instead of throwing (this was a real bug: every
            // other comparison in this language is case-insensitive except this one).
            Expression<?> comparablePath = switch (type) {
                case STRING, ENUM, STRING_LIST -> cb.lower(path.as(String.class));
                case NUMBER -> path.as(Double.class);
                case PRIORITY -> path.as(Integer.class);
                case BOOLEAN -> path.as(Boolean.class);
                case DATE -> path.as(OffsetDateTime.class);
            };
            List<Object> coerced = list.items().stream().map(s -> coerceScalar(type, s)).toList();
            Predicate in = comparablePath.in(coerced);
            return op == AqlOperator.NEQ ? cb.not(in) : in;
        }

        AqlValue.Scalar scalar = (AqlValue.Scalar) value;
        return switch (op) {
            case EQ -> equalityPredicate(path, type, scalar, cb, false);
            case NEQ -> equalityPredicate(path, type, scalar, cb, true);
            case CONTAINS -> {
                if (type != AqlFieldType.STRING) {
                    throw new AqlCompileException("'~=' (contains) is only supported for string fields");
                }
                yield cb.like(cb.lower(path.as(String.class)), "%" + scalar.raw().toLowerCase(Locale.ROOT) + "%");
            }
            case GT, GTE, LT, LTE -> rangePredicate(path, type, op, scalar, cb);
            case HAS -> arrayContainsPredicate(path, type, scalar, cb);
            case IN -> throw new IllegalStateException("unreachable — IN always carries a ListValue");
        };
    }

    /** {@code ares.array_contains_ci(array, value)} (V157) — a small SQL helper doing {@code
     *  EXISTS (SELECT 1 FROM unnest(array) elem WHERE LOWER(elem) = LOWER(value))}, called via
     *  {@code cb.function} the same way {@link #compileJsonbPath} already calls {@code
     *  jsonb_extract_path_text}. Works correctly regardless of whether the array's own contents
     *  are pre-lowercased at write time (most HAS-queryable arrays in this codebase are) or keep
     *  their original display case (e.g. {@code attack_technique.platforms}/{@code
     *  dataSources}/{@code permissionsRequired}, rendered directly as badges by ares-ui) — so every
     *  HAS-queryable array gets AQL's usual case-insensitive comparison uniformly, without a
     *  per-field exception a caller could get wrong. An earlier version of this method used the
     *  built-in {@code pg_catalog.array_position(...) IS NOT NULL} instead, which only worked
     *  because it assumed every array was already lowercase — a real limitation, not a deliberate
     *  design choice, that meant display-case-preserved arrays couldn't be registered HAS-queryable
     *  at all without silently breaking that invariant.
     *
     *  <p>This replaced a version schema-qualified as {@code pg_catalog.array_position} specifically
     *  to dodge Hibernate 6.4+'s own registered {@code array_position} function (which intercepts a
     *  bare-named call and rewrites it into a portability shim broken when composed with {@code IS
     *  NOT NULL} — a real bug caught once already this initiative). {@code ares.array_contains_ci}
     *  needs no such qualification since it isn't a name Hibernate's function registry knows about
     *  at all, but the schema-qualification habit is kept anyway since it costs nothing and rules
     *  out any future collision the same way. */
    private Predicate arrayContainsPredicate(Expression<?> path, AqlFieldType type, AqlValue.Scalar scalar, CriteriaBuilder cb) {
        if (type != AqlFieldType.STRING_LIST) {
            throw new AqlCompileException("HAS is only supported for array-valued fields");
        }
        return cb.isTrue(cb.function("ares.array_contains_ci", Boolean.class, path, cb.literal(scalar.raw())));
    }

    private Predicate equalityPredicate(Expression<?> path, AqlFieldType type, AqlValue.Scalar scalar, CriteriaBuilder cb, boolean negate) {
        Predicate eq = switch (type) {
            case STRING, ENUM, STRING_LIST ->
                cb.equal(cb.lower(path.as(String.class)), scalar.raw().toLowerCase(Locale.ROOT));
            case NUMBER -> cb.equal(path.as(Double.class), parseNumber(scalar));
            case PRIORITY -> cb.equal(path.as(Integer.class), parsePriority(scalar));
            case BOOLEAN -> cb.equal(path.as(Boolean.class), parseBoolean(scalar));
            case DATE -> cb.equal(path.as(OffsetDateTime.class), parseDate(scalar));
        };
        return negate ? cb.not(eq) : eq;
    }

    private Predicate rangePredicate(Expression<?> path, AqlFieldType type, AqlOperator op, AqlValue.Scalar scalar, CriteriaBuilder cb) {
        if (type == AqlFieldType.DATE) {
            Expression<OffsetDateTime> expr = path.as(OffsetDateTime.class);
            OffsetDateTime v = parseDate(scalar);
            return switch (op) {
                case GT -> cb.greaterThan(expr, v);
                case GTE -> cb.greaterThanOrEqualTo(expr, v);
                case LT -> cb.lessThan(expr, v);
                case LTE -> cb.lessThanOrEqualTo(expr, v);
                default -> throw new IllegalStateException("unreachable");
            };
        }
        if (type == AqlFieldType.NUMBER) {
            Expression<Double> expr = path.as(Double.class);
            double v = parseNumber(scalar);
            return switch (op) {
                case GT -> cb.greaterThan(expr, v);
                case GTE -> cb.greaterThanOrEqualTo(expr, v);
                case LT -> cb.lessThan(expr, v);
                case LTE -> cb.lessThanOrEqualTo(expr, v);
                default -> throw new IllegalStateException("unreachable");
            };
        }
        if (type == AqlFieldType.PRIORITY) {
            Expression<Integer> expr = path.as(Integer.class);
            int v = parsePriority(scalar);
            return switch (op) {
                case GT -> cb.greaterThan(expr, v);
                case GTE -> cb.greaterThanOrEqualTo(expr, v);
                case LT -> cb.lessThan(expr, v);
                case LTE -> cb.lessThanOrEqualTo(expr, v);
                default -> throw new IllegalStateException("unreachable");
            };
        }
        throw new AqlCompileException("Operator '" + op + "' is not supported for field type " + type);
    }

    private Object coerceScalar(AqlFieldType type, AqlValue.Scalar scalar) {
        return switch (type) {
            case STRING, ENUM, STRING_LIST -> scalar.raw().toLowerCase(Locale.ROOT);
            case NUMBER -> parseNumber(scalar);
            case PRIORITY -> parsePriority(scalar);
            case BOOLEAN -> parseBoolean(scalar);
            case DATE -> parseDate(scalar);
        };
    }

    private double parseNumber(AqlValue.Scalar scalar) {
        return AqlScalarParsing.parseNumber(scalar);
    }

    private int parsePriority(AqlValue.Scalar scalar) {
        return AqlScalarParsing.parsePriority(scalar);
    }

    private boolean parseBoolean(AqlValue.Scalar scalar) {
        return AqlScalarParsing.parseBoolean(scalar);
    }

    private OffsetDateTime parseDate(AqlValue.Scalar scalar) {
        return AqlDateLiterals.resolve(scalar.raw());
    }
}
