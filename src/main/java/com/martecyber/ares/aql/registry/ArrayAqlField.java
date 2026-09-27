package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.Set;
import java.util.function.Function;

/**
 * A native Postgres array column (e.g. {@code cve.cwes: text[]}) — see {@link AqlFieldKind#ARRAY_COLUMN}.
 * {@code supportedOperators} should almost always just be {@code Set.of(AqlOperator.HAS)}; nothing
 * stops a registry granting more, but EQ/NEQ against "the whole array equals X" has no compiler
 * support and would fail at query time, not registration time.
 *
 * <p>HAS is case-insensitive regardless of the array's own stored case (via {@code
 * ares.array_contains_ci}, V157) — array contents don't need to be lowercase-normalized at write
 * time for this field to be registered here, though most HAS-queryable arrays in this codebase
 * still are, by convention/precedent rather than requirement. The exception is fields ares-ui
 * renders directly as display strings (e.g. {@code attackTechnique.platforms}) — those deliberately
 * keep their original case in storage, and HAS still matches them case-insensitively regardless.
 */
public record ArrayAqlField<T>(
    String name,
    Set<AqlOperator> supportedOperators,
    Function<Root<T>, Path<?>> pathResolver
) implements PostgresColumnField<T> {

    @Override
    public AqlFieldType type() {
        return AqlFieldType.STRING_LIST;
    }

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.ARRAY_COLUMN;
    }

    @Override
    public Path<?> resolvePath(Root<T> root) {
        return pathResolver.apply(root);
    }
}
