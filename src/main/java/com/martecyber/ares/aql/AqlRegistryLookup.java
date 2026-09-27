package com.martecyber.ares.aql;

import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Resolves an AQL-queryable entity name (e.g. "detection") to its registry — every {@link
 *  EntityAqlRegistry} Spring bean registers itself here by {@link EntityAqlRegistry#entityName()},
 *  so the /api/v1/aql endpoints and any future service (Workflows) don't need their own
 *  entity-name switch statement. */
@Component
public class AqlRegistryLookup {

    private final Map<String, EntityAqlRegistry<?>> byName;

    public AqlRegistryLookup(List<EntityAqlRegistry<?>> registries) {
        this.byName = registries.stream()
            .collect(Collectors.toMap(EntityAqlRegistry::entityName, r -> r));
        // Second pass, after every registry is indexed — lets a registry with RelationAqlFields
        // (asset.detections.*, finding.assets.*, ...) look up ANY other registry, including ones
        // it can't safely constructor-inject (most of these relations are mutual/bidirectional),
        // and eagerly flatten "<relation>.<leaf>" entries into its own field map. A no-op for the
        // overwhelming majority of registries, which have no relations.
        //
        // Run this pass EXPANSION_ROUNDS times, not once: a relation chain can itself be two hops
        // deep now (Detection -> cve -> kev, since CveAqlRegistry registers its own "kev" relation
        // — Phase 4 of the AQL-wide initiative). RelationExpansion.expandOne snapshots the TARGET
        // registry's field list at the moment it runs; registries.forEach iterates in whatever
        // order the injected List<EntityAqlRegistry<?>> happens to be in, which isn't guaranteed
        // topological (and can't be made so in general — most relations here are mutual/
        // bidirectional, e.g. asset<->detection<->finding, so no single topological order exists).
        // One extra round is cheap (a handful of registries, run once at startup) and lets any
        // registry that expanded a relation in round N have that be visible to a registry
        // depending on it in round N+1 — e.g. round 1 gives CveAqlRegistry its "kev.*" leaves
        // regardless of when in the pass it runs; round 2 guarantees DetectionAqlRegistry's own
        // "cve.*" flattening (which runs against CveAqlRegistry's CURRENT field list) picks up
        // "cve.kev.*" too, however round 1 happened to order the two. Bounded rather than
        // fixed-point/until-stable specifically so a future accidental relation cycle fails loud
        // (unresolved "<relation>.<leaf>" entries) instead of hanging forever.
        final int EXPANSION_ROUNDS = 3;
        for (int round = 0; round < EXPANSION_ROUNDS; round++) {
            registries.forEach(r -> r.expandRelations(this));
        }
    }

    public EntityAqlRegistry<?> require(String entity) {
        EntityAqlRegistry<?> registry = byName.get(entity);
        if (registry == null) {
            throw new IllegalArgumentException("Unknown AQL entity '" + entity + "'. Known entities: "
                + String.join(", ", byName.keySet()));
        }
        return registry;
    }

    public List<String> entityNames() {
        return List.copyOf(byName.keySet());
    }
}
