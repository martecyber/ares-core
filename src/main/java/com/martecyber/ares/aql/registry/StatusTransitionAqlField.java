package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Virtual DATE field for {@code status.<statusName>} — "the moment T's status first transitioned
 * into statusName" (MIN of the matching status-history rows' timestamp), compiled to a correlated
 * scalar subquery and compared directly via the usual DATE operators ({@code status.affected <
 * 2026-06-01}), not an EXISTS.
 *
 * <p>Deliberately NOT a {@link RelationAqlField}/{@link RelationLeafAqlField}: those compile every
 * {@code relation.leaf} comparison to its own independent correlated EXISTS, so ANDing two of them
 * means "some related row (possibly a different one for each) satisfies each" — see {@code
 * PostgresSpecificationCompiler}'s class javadoc. That would be flatly wrong here: {@code
 * status.affected < X AND status.closed > Y} must mean two genuinely independent facts about the
 * SAME entity ("it first became affected before X" and "it first became closed after Y"), which a
 * shared-relation EXISTS shape can't express (there's no single "status history row" that is
 * simultaneously the affected-transition and the closed-transition). Giving each status name its
 * own field — each resolving to its own single scalar subquery expression — sidesteps the problem
 * entirely: {@code status.affected} and {@code status.closed} are just two unrelated DATE-typed
 * expressions, exactly like any other pair of DATE columns.
 *
 * <p>Registered directly (one instance per known status name) in each entity's own
 * {@code *AqlRegistry} constructor under the literal name {@code "status." + statusName} — no
 * {@link AqlRegistryLookup} flattening pass involved, since the set of statuses is already known
 * at registry-construction time (unlike a general relation's target field list).
 */
public record StatusTransitionAqlField<T>(
    String name,
    String statusName,
    StatusTransitionAqlField.SubqueryBuilder<T> subqueryBuilder
) implements AqlField<T> {

    private static final Set<AqlOperator> DATE_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);

    @Override
    public AqlFieldType type() {
        return AqlFieldType.DATE;
    }

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.STATUS_TRANSITION;
    }

    @Override
    public Set<AqlOperator> supportedOperators() {
        return DATE_OPS;
    }

    /** Builds {@code SELECT MIN(changed_at) FROM <history> WHERE <correlated to root> AND <status
     *  matches statusName>} as a correlated scalar {@link jakarta.persistence.criteria.Subquery}
     *  (which itself implements {@code Expression}, so it composes directly with the compiler's
     *  usual {@code cb.lessThan}/{@code cb.equal}/etc — no separate comparison machinery needed). */
    public Expression<OffsetDateTime> resolveExpression(Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb) {
        return subqueryBuilder.build(root, query, cb, statusName);
    }

    @FunctionalInterface
    public interface SubqueryBuilder<T> {
        Expression<OffsetDateTime> build(Root<T> root, AbstractQuery<?> query, CriteriaBuilder cb, String statusName);
    }
}
