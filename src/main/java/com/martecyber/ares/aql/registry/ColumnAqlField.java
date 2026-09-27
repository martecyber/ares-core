package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public record ColumnAqlField<T>(
    String name,
    AqlFieldType type,
    AqlFieldKind kind,
    Set<AqlOperator> supportedOperators,
    Function<Root<T>, Path<?>> pathResolver,
    List<String> allowedValues,
    /** Optional — null unless a registry explicitly opts this field into in-memory evaluation
     *  (Workflow CONDITION nodes). Most fields never need this; DB-query compilation never reads it. */
    Function<T, Object> valueResolver
) implements PostgresColumnField<T>, InMemoryResolvableField<T> {

    /** Free-text/unbounded field — the overwhelming majority (title, description, timestamps…). */
    public ColumnAqlField(String name, AqlFieldType type, AqlFieldKind kind,
                          Set<AqlOperator> supportedOperators, Function<Root<T>, Path<?>> pathResolver) {
        this(name, type, kind, supportedOperators, pathResolver, List.of(), null);
    }

    public ColumnAqlField(String name, AqlFieldType type, AqlFieldKind kind,
                          Set<AqlOperator> supportedOperators, Function<Root<T>, Path<?>> pathResolver,
                          List<String> allowedValues) {
        this(name, type, kind, supportedOperators, pathResolver, allowedValues, null);
    }

    @Override
    public Path<?> resolvePath(Root<T> root) {
        return pathResolver.apply(root);
    }

    @Override
    public Object resolveValue(T entity) {
        if (valueResolver == null) {
            throw new com.martecyber.ares.aql.compile.AqlCompileException(
                "Field '" + name + "' has no in-memory value resolver registered — it can be used "
                    + "in DB-backed AQL queries but not in a Workflow CONDITION node yet");
        }
        return valueResolver.apply(entity);
    }
}
