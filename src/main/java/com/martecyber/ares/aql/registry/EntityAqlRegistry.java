package com.martecyber.ares.aql.registry;

import java.util.List;
import java.util.Optional;

/** Declares which AQL fields exist for one queryable entity (Detection, Finding, Asset, ...). */
public interface EntityAqlRegistry<T> {

    String entityName();

    Optional<AqlField<T>> field(String name);

    default AqlField<T> requireField(String name) {
        return field(name).orElseThrow(() -> new AqlFieldNotFoundException(entityName(), name));
    }

    /** Fields a bare (unscoped) term searches across, e.g. title/description — mirrors today's
     *  "q" behavior. The compiler casts to the field kind it knows how to handle
     *  (PostgresColumnField) and rejects the rest. */
    List<AqlField<T>> defaultSearchFields();

    /** Every field this entity exposes to AQL — for UI/CLI autocomplete (GET /api/v1/aql/fields), so those don't hardcode a duplicate field list that can drift from the registry. */
    List<AqlField<T>> allFields();

    /** Called once by {@code AqlRegistryLookup} right after every {@code EntityAqlRegistry} bean
     *  is constructed — the hook a registry with {@link RelationAqlField}s uses to look up their
     *  target registries (by entity name, via the now-complete lookup) and eagerly flatten each
     *  target field into {@code "<relation>.<leaf>"} entries. Deliberately a post-construction
     *  pass rather than constructor injection: most relations here are mutual/bidirectional
     *  (asset&lt;-&gt;detection, detection&lt;-&gt;finding, finding&lt;-&gt;asset), which Spring can't wire via
     *  plain constructor injection without a circular-bean error. A no-op for registries with no
     *  relations — the overwhelming majority. */
    default void expandRelations(com.martecyber.ares.aql.AqlRegistryLookup lookup) {}
}
