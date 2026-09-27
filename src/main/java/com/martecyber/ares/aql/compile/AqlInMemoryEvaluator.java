package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.parser.AqlValue;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.InMemoryResolvableField;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates a parsed {@link AqlNode} directly against an already-loaded {@code T} instance — no
 * DB round trip. The counterpart to {@link PostgresSpecificationCompiler}, which only ever
 * produces DB-query artifacts; this is what a Workflow CONDITION node needs (Workflows
 * implementation plan, Phase A — the hook AQL's own Phase 6 note left open).
 *
 * <p>Only fields implementing {@link InMemoryResolvableField} can be evaluated this way — today
 * that's PHYSICAL_COLUMN/VIRTUAL fields a registry has explicitly opted in with a value-resolver
 * lambda (see {@code ColumnAqlField.valueResolver}). JSONB_PATH, KB_MATERIALIZED and RELATION
 * fields are rejected with a clear {@link AqlCompileException} rather than silently misevaluated —
 * the first needs generic jsonb/reflection support that doesn't exist yet, and the other two are
 * structurally cross-row (correlated subqueries), incompatible with pure in-memory evaluation by
 * nature, not just by omission.
 */
public final class AqlInMemoryEvaluator {

    private AqlInMemoryEvaluator() {}

    public static <T> boolean matches(T entity, AqlNode node, EntityAqlRegistry<T> registry) {
        return switch (node) {
            case AqlNode.And and -> and.operands().stream().allMatch(n -> matches(entity, n, registry));
            case AqlNode.Or or -> or.operands().stream().anyMatch(n -> matches(entity, n, registry));
            case AqlNode.Not not -> !matches(entity, not.operand(), registry);
            case AqlNode.Comparison cmp -> evaluateComparison(entity, cmp, registry);
            case AqlNode.BareTerm term -> evaluateBareTerm(entity, term, registry);
        };
    }

    private static <T> boolean evaluateComparison(T entity, AqlNode.Comparison cmp, EntityAqlRegistry<T> registry) {
        AqlField<T> field = registry.requireField(cmp.field());
        if (!field.supportedOperators().contains(cmp.operator())) {
            throw new AqlCompileException(
                "Field '" + field.name() + "' does not support operator '" + cmp.operator() + "'");
        }
        InMemoryResolvableField<T> resolvable = asResolvable(field);
        if (resolvable == null) {
            throw new AqlCompileException(
                "Field '" + field.name() + "' (" + field.kind() + ") cannot be evaluated in-memory — "
                    + "only fields with a registered in-memory value resolver support Workflow CONDITION nodes today");
        }
        Object actual = resolvable.resolveValue(entity);
        return compareValue(actual, field.type(), cmp.operator(), cmp.value());
    }

    private static <T> boolean evaluateBareTerm(T entity, AqlNode.BareTerm term, EntityAqlRegistry<T> registry) {
        List<AqlField<T>> searchFields = registry.defaultSearchFields();
        if (searchFields.isEmpty()) {
            throw new AqlCompileException("Entity '" + registry.entityName() + "' has no free-text search fields");
        }
        String needle = term.text().toLowerCase(Locale.ROOT);
        return searchFields.stream().anyMatch(f -> {
            InMemoryResolvableField<T> resolvable = asResolvable(f);
            if (resolvable == null) {
                throw new AqlCompileException(
                    "Default search field '" + f.name() + "' cannot be evaluated in-memory");
            }
            Object actual = resolvable.resolveValue(entity);
            return actual != null && actual.toString().toLowerCase(Locale.ROOT).contains(needle);
        });
    }

    /** {@link AqlField} and {@link InMemoryResolvableField} are unrelated interface hierarchies
     *  (unlike {@link com.martecyber.ares.aql.registry.PostgresColumnField}, which extends
     *  AqlField and so gets a checked generic pattern match) — a plain {@code instanceof} pattern
     *  variable can't carry the {@code T} type argument across them, so this does the raw-type
     *  check and an explicitly-suppressed cast instead. Safe by construction: every concrete
     *  {@code AqlField<T>} that also implements {@code InMemoryResolvableField} does so for that
     *  same {@code T} (see {@code ColumnAqlField}), never a mismatched one. */
    @SuppressWarnings("unchecked")
    private static <T> InMemoryResolvableField<T> asResolvable(AqlField<T> field) {
        return field instanceof InMemoryResolvableField ? (InMemoryResolvableField<T>) field : null;
    }

    private static boolean compareValue(Object actual, AqlFieldType type, AqlOperator op, AqlValue value) {
        if (value instanceof AqlValue.ListValue list) {
            // IN behaves exactly like EQ against a list (see AqlOperator's own doc comment) — the
            // parser guarantees IN never arrives with anything but a ListValue, so no separate
            // validation is needed here beyond widening the existing EQ/NEQ guard.
            if (op != AqlOperator.EQ && op != AqlOperator.NEQ && op != AqlOperator.IN) {
                throw new AqlCompileException("Operator '" + op + "' does not accept a list value");
            }
            boolean in = list.items().stream().anyMatch(s -> valuesEqual(actual, type, s));
            return op == AqlOperator.NEQ ? !in : in;
        }
        AqlValue.Scalar scalar = (AqlValue.Scalar) value;
        return switch (op) {
            case EQ -> valuesEqual(actual, type, scalar);
            case NEQ -> !valuesEqual(actual, type, scalar);
            case CONTAINS -> {
                if (type != AqlFieldType.STRING) {
                    throw new AqlCompileException("'~=' (contains) is only supported for string fields");
                }
                yield actual != null
                    && actual.toString().toLowerCase(Locale.ROOT).contains(scalar.raw().toLowerCase(Locale.ROOT));
            }
            case GT, GTE, LT, LTE -> actual != null && compareOrdered(actual, type, op, scalar);
            // The parser guarantees HAS's right-hand side is always a Scalar, never a ListValue —
            // the field itself is what's multi-valued, resolved here as a Collection/array.
            case HAS -> hasMembership(actual, scalar);
            case IN -> throw new IllegalStateException("unreachable — IN always carries a ListValue");
        };
    }

    /** IN-MEMORY membership check for HAS — the DB-backed compilers instead compile this against
     *  a real {@code ARRAY_COLUMN}-kind field; here the already-resolved {@code actual} value just
     *  needs to actually be a collection or array. Case-insensitive, matching every other string
     *  comparison in this language. */
    private static boolean hasMembership(Object actual, AqlValue.Scalar scalar) {
        if (actual == null) return false;
        String needle = scalar.raw();
        if (actual instanceof Object[] arr) {
            return java.util.Arrays.stream(arr).anyMatch(e -> e != null && e.toString().equalsIgnoreCase(needle));
        }
        if (actual instanceof Iterable<?> it) {
            for (Object e : it) {
                if (e != null && e.toString().equalsIgnoreCase(needle)) return true;
            }
            return false;
        }
        throw new AqlCompileException("HAS requires a collection/array-valued field, got " + actual.getClass().getSimpleName());
    }

    private static boolean valuesEqual(Object actual, AqlFieldType type, AqlValue.Scalar scalar) {
        if (actual == null) return false;
        return switch (type) {
            case STRING, ENUM, STRING_LIST -> actual.toString().equalsIgnoreCase(scalar.raw());
            case NUMBER -> asDouble(actual) == AqlScalarParsing.parseNumber(scalar);
            case PRIORITY -> asInt(actual) == AqlScalarParsing.parsePriority(scalar);
            case BOOLEAN -> asBoolean(actual) == AqlScalarParsing.parseBoolean(scalar);
            case DATE -> asDate(actual).isEqual(AqlDateLiterals.resolve(scalar.raw()));
        };
    }

    private static boolean compareOrdered(Object actual, AqlFieldType type, AqlOperator op, AqlValue.Scalar scalar) {
        int cmp = switch (type) {
            case NUMBER -> Double.compare(asDouble(actual), AqlScalarParsing.parseNumber(scalar));
            case PRIORITY -> Integer.compare(asInt(actual), AqlScalarParsing.parsePriority(scalar));
            case DATE -> asDate(actual).compareTo(AqlDateLiterals.resolve(scalar.raw()));
            default -> throw new AqlCompileException("Operator '" + op + "' is not supported for field type " + type);
        };
        return switch (op) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private static double asDouble(Object v) {
        return ((Number) v).doubleValue();
    }

    private static int asInt(Object v) {
        return ((Number) v).intValue();
    }

    private static boolean asBoolean(Object v) {
        return (Boolean) v;
    }

    private static OffsetDateTime asDate(Object v) {
        return (OffsetDateTime) v;
    }
}
