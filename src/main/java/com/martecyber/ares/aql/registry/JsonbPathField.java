package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;

import java.util.List;
import java.util.Set;

/**
 * A dynamic/custom field inside a typed jsonb column (Asset.metadata / Finding.fields — AQL
 * implementation plan, V141/V142), validated against field_definition. Compiles to
 * jsonb_extract_path_text(&lt;jsonbColumn&gt;, &lt;jsonKey&gt;), cast per {@link #type()} —
 * the same PostgreSQL function the {@code ->>} operator itself compiles to at the SQL level, just
 * invocable from JPA Criteria without a native query.
 *
 * @param jsonbColumn   the JPA attribute name of the jsonb column on the owning entity (e.g. "metadata", "fields")
 * @param jsonKey       the key inside that jsonb object (e.g. "osVersion")
 * @param allowedValues the field_definition row's allowed_values, when it's an ENUM (empty for free-text keys)
 */
public record JsonbPathField<T>(
    String name,
    AqlFieldType type,
    Set<AqlOperator> supportedOperators,
    String jsonbColumn,
    String jsonKey,
    List<String> allowedValues
) implements AqlField<T> {

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.JSONB_PATH;
    }
}
