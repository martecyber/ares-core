package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;

import java.util.Set;

/**
 * A KB hot field mirrored onto Postgres (kb_materialized_ref, V145) — compiles to a correlated
 * EXISTS subquery joining T -&gt; ReferenceEntry -&gt; kb_materialized_ref, never touching Mongo.
 *
 * @param referencesInverseAttribute the JPA attribute on ReferenceEntry that maps back to T
 *                                   (e.g. "detections" for Detection) — entity-specific, supplied
 *                                   by each entity's registry.
 * @param materializedAttribute     the JPA attribute on KbMaterializedRef to compare against
 *                                  (e.g. "kevListed").
 */
public record KbMaterializedField<T>(
    String name,
    AqlFieldType type,
    Set<AqlOperator> supportedOperators,
    Long catalogId,
    String referencesInverseAttribute,
    String materializedAttribute
) implements AqlField<T> {

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.KB_MATERIALIZED;
    }
}
