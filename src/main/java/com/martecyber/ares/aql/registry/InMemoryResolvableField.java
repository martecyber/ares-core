package com.martecyber.ares.aql.registry;

/**
 * An {@link AqlField} that can be evaluated against an already-loaded Java object, with no DB
 * round trip — the capability {@link com.martecyber.ares.aql.compile.AqlInMemoryEvaluator} needs
 * for Workflow CONDITION nodes (Workflows implementation plan, Phase A). Distinct from
 * {@link PostgresColumnField}: that resolves to a JPA Criteria {@link jakarta.persistence.criteria.Path},
 * usable only inside a query; this resolves a concrete value from a live {@code T} instance.
 *
 * <p>Only {@link ColumnAqlField} (PHYSICAL_COLUMN/VIRTUAL) implements this today, and only for
 * fields a registry explicitly opts in with a resolver lambda — JSONB_PATH/KB_MATERIALIZED/
 * RELATION fields are not in-memory-resolvable and deliberately don't implement this interface,
 * so {@code AqlInMemoryEvaluator} rejects them with a clear typed exception rather than guessing.
 */
public interface InMemoryResolvableField<T> {
    Object resolveValue(T entity);
}
