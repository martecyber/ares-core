package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;

import java.util.List;
import java.util.Set;

/**
 * One {@code "<relation>.<leaf>"} flat entry — e.g. {@code "detections.priority"} on Asset's
 * registry — built by {@link AqlRegistryLookup}'s post-construction expansion pass, one per field
 * the target registry exposes at expansion time. Carries the ALREADY-RESOLVED target registry
 * (safe to store here, unlike on {@link RelationAqlField} itself, since this is only ever built
 * after every registry — including the target — is fully constructed) so the compiler can
 * recursively compile the leaf comparison against it.
 */
public record RelationLeafAqlField<T, R>(
    String name,
    RelationAqlField<T, R> relation,
    EntityAqlRegistry<R> targetRegistry,
    AqlField<R> leaf
) implements AqlField<T> {

    @Override
    public AqlFieldType type() {
        return leaf.type();
    }

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.RELATION;
    }

    @Override
    public Set<AqlOperator> supportedOperators() {
        return leaf.supportedOperators();
    }

    @Override
    public List<String> allowedValues() {
        return leaf.allowedValues();
    }
}
