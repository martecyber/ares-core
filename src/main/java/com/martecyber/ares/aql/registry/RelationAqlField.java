package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.Set;

/**
 * A relation to another Postgres entity R, one or more hops away, correlated back to T via a
 * correlated EXISTS subquery — same store both sides, never a Mongo round trip. Registered under
 * its own bare name (e.g. "detections") but never itself directly comparable — {@code
 * supportedOperators()} is empty, so a bare {@code detections == x} is rejected by the ordinary
 * operator-whitelist check with a clear message. What's actually queryable is the flattened
 * {@code "<relation>.<leaf>"} entries {@link AqlRegistryLookup} builds from this after every
 * registry is constructed (see its constructor) — deliberately NOT built by injecting the target
 * registry directly into this one's constructor, because most of the relations in this codebase
 * are mutual/bidirectional (asset&lt;-&gt;detection, detection&lt;-&gt;finding, finding&lt;-&gt;asset), which
 * Spring's constructor injection can't resolve without a circular-bean-creation error. Instead
 * this only carries the target's entity NAME (a String, resolved against {@link AqlRegistryLookup}
 * once every registry exists) and its Class (a plain class literal, never circular).
 */
public record RelationAqlField<T, R>(
    String name,
    String targetEntityName,
    Class<R> targetEntityClass,
    RelationAqlField.RelationCorrelation<T, R> correlation,
    /** Non-null only for relations built via {@link #listOf}, both null otherwise. When set, {@link
     *  com.martecyber.ares.aql.compile.PostgresSpecificationCompiler#compileRelation} takes a
     *  completely different (and much cheaper) compilation path than {@code correlation} describes
     *  — see that method's own doc comment for why a per-outer-row correlated EXISTS is
     *  catastrophically slow for this specific shape, and why array_agg + overlap isn't. {@code
     *  correlation} is still populated (mirroring the old unnest-based predicate) so any other
     *  reader of this record — e.g. straight-line debugging — never has to special-case a null
     *  correlation, even though the compiler no longer calls it for these fields. */
    String arrayAttribute,
    String targetIdAttribute
) implements AqlField<T> {

    /** Plain FK/bridge-table relations (Asset&lt;-&gt;Detection&lt;-&gt;Finding, cve.kev, ATT&amp;CK
     *  technique&lt;-&gt;tactic/mitigation, ...) — every call site that isn't {@link #listOf}. */
    public RelationAqlField(String name, String targetEntityName, Class<R> targetEntityClass,
                             RelationAqlField.RelationCorrelation<T, R> correlation) {
        this(name, targetEntityName, targetEntityClass, correlation, null, null);
    }

    @Override
    public AqlFieldType type() {
        return AqlFieldType.STRING; // unused — never compared against directly, see class javadoc
    }

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.RELATION;
    }

    @Override
    public Set<AqlOperator> supportedOperators() {
        return Set.of();
    }

    /** Builds the WHERE predicate correlating the outer T root to a target R root the compiler has
     *  already added to the subquery via {@code subquery.from(targetEntityClass)} — free to add
     *  further intermediate roots/joins to {@code subquery} for multi-hop bridges (see
     *  DetectionAqlRegistry/FindingAqlRegistry/AssetAqlRegistry for the Affection-bridged cases).
     *  Never includes the recursively-compiled leaf comparison on R itself — the compiler ANDs
     *  that in separately. */
    @FunctionalInterface
    public interface RelationCorrelation<T, R> {
        Predicate correlate(Root<T> outerRoot, Root<R> targetRoot, AbstractQuery<?> subquery, CriteriaBuilder cb);
    }

    /**
     * A {@code RelationAqlField} correlated via native-array membership rather than a join/bridge
     * table — the AQL surface's {@code list[X]} shape for a {@code text[]} column that holds
     * another entity's identifier codes (e.g. {@code CveEntry.cwes} holding CWE IDs) rather than
     * plain display strings. {@code cve.cwes} used to be a bare {@link ArrayAqlField} (HAS-only:
     * {@code cwes HAS "CWE-79"}); as a {@code list[cwe]} relation it's no longer directly
     * comparable itself (same as every other bare {@code RelationAqlField}) but exposes every CWE
     * field through the usual flattened {@code "cwes.<leaf>"} entries — {@code cwes.id ==
     * "CWE-79"} resolves to the same CVEs {@code cwes HAS "CWE-79"} used to, plus lets a query
     * reach {@code cwes.name}, {@code cwes.abstraction}, etc. Reuses {@code
     * ares.array_contains_ci} (V157) — the exact same case-insensitive membership check HAS
     * already used — so the correlation semantics don't drift from HAS's, and no new SQL is
     * needed. Plain string arrays with no corresponding entity (platforms, preventions, ...) stay
     * {@link ArrayAqlField} — {@code list[string]} in AQL terms, unaffected by this.
     */
    public static <T, R> RelationAqlField<T, R> listOf(String name, String targetEntityName, Class<R> targetEntityClass,
                                                         String arrayAttribute, String targetIdAttribute) {
        return new RelationAqlField<>(name, targetEntityName, targetEntityClass,
            (sourceRoot, targetRoot, sub, cb) -> cb.isTrue(cb.function("ares.array_contains_ci", Boolean.class,
                sourceRoot.get(arrayAttribute), targetRoot.get(targetIdAttribute))),
            arrayAttribute, targetIdAttribute);
    }
}
