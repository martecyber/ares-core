package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;

import java.util.List;
import java.util.Set;

/**
 * Storage-agnostic description of one queryable AQL field. Deliberately carries no JPA/Mongo
 * specifics here — {@link PostgresColumnField} (and future jsonb/KB-field interfaces) add the
 * store-specific resolution a given compiler needs.
 */
public interface AqlField<T> {
    String name();
    AqlFieldType type();
    AqlFieldKind kind();
    Set<AqlOperator> supportedOperators();

    /** Closed set of legal values, when known ahead of time (e.g. an ENUM {@code field_definition}
     *  row, a relational status catalog, or the fixed P0-P4 priority scale) — powers UI/CLI
     *  autocomplete suggesting real values instead of just field names and operators. Empty for
     *  free-text fields, which is the overwhelming majority. */
    default List<String> allowedValues() {
        return List.of();
    }

    /** The fixed P0-P4 label set every PRIORITY-typed field shares — a shorthand for registries to
     *  pass as {@code allowedValues} when registering a priority field, so the list literal isn't
     *  repeated at every call site. */
    List<String> PRIORITY_LABELS = List.of("P0", "P1", "P2", "P3", "P4");
}
