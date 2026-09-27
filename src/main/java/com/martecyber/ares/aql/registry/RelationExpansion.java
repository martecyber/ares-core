package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.AqlRegistryLookup;

import java.util.List;
import java.util.Map;

/**
 * Shared implementation of the {@link RelationAqlField} flat-expansion pass every registry with
 * relations needs — see {@link EntityAqlRegistry#expandRelations} and {@link RelationAqlField}'s
 * own javadoc for why this has to be a post-construction pass rather than constructor injection
 * (most relations in this codebase are mutual/bidirectional — asset&lt;-&gt;detection, detection&lt;-&gt;
 * finding, finding&lt;-&gt;asset — which Spring can't resolve via plain constructor DI without a
 * circular-bean error). A registry implementing {@code expandRelations} just calls {@link #expand}
 * with its own mutable field map; this does the rest.
 */
public final class RelationExpansion {

    /** Caps how many hops a flattened relation path can reach (dot count in the field name) — the
     *  AQL-wide plan flagged this as worth adding defensively even when no cycle was known to
     *  exist yet ("No cycles are possible in this graph... still worth a defensive max-depth guard
     *  (e.g. 4)"), and it turned out to matter for real: {@code AttackAqlRegistry}'s "mitigations"
     *  and {@code AttackMitigationAqlRegistry}'s "techniques" are a genuine mutual/bidirectional
     *  pair, and {@link com.martecyber.ares.aql.AqlRegistryLookup}'s multi-round expansion (needed
     *  for legitimate non-cyclic 2-3 hop chains like {@code cve.kev.*} and {@code
     *  detection.attack.mitigations.*}) re-expands EVERY registry's relations every round —
     *  including already-flattened ones from a prior round — so a cyclic pair compounds by ~2 hops
     *  per round instead of converging, producing field names like {@code
     *  mitigations.techniques.mitigations.techniques.mitigations} with no real analyst use case.
     *  3 preserves every legitimate chain this codebase actually uses (the deepest confirmed one,
     *  {@code detection.attack.mitigations.id}, is 3 hops — {@code attack.mitigations.id}
     *  once flattened onto DetectionAqlRegistry, 2 dots) while cutting off runaway cyclic growth at
     *  the source instead of just bounding the round count (which only limits the DEPTH the
     *  explosion reaches per round, not whether it happens at all) — 4 was tried first and still
     *  let the cyclic pair complete one full technique→mitigation→technique→mitigation round trip
     *  before landing on a leaf ({@code mitigations.techniques.mitigations.techniques.platforms}),
     *  which is a real path with no realistic analyst use, not a false positive being over-cut. */
    private static final int MAX_RELATION_DEPTH = 3;

    private RelationExpansion() {}

    public static <T> void expand(Map<String, AqlField<T>> fields, AqlRegistryLookup lookup) {
        // Snapshot first — expandOne mutates `fields` as it goes (adding the flattened leaves),
        // and we only ever want to expand the RelationAqlFields present BEFORE this pass started.
        for (AqlField<T> f : List.copyOf(fields.values())) {
            if (f instanceof RelationAqlField<T, ?> relation) {
                expandOne(fields, lookup, relation);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, R> void expandOne(Map<String, AqlField<T>> fields, AqlRegistryLookup lookup,
                                          RelationAqlField<T, R> relation) {
        EntityAqlRegistry<R> targetRegistry = (EntityAqlRegistry<R>) lookup.require(relation.targetEntityName());
        for (AqlField<R> leaf : targetRegistry.allFields()) {
            String name = relation.name() + "." + leaf.name();
            if (depth(name) > MAX_RELATION_DEPTH) continue;
            fields.put(name, new RelationLeafAqlField<>(name, relation, targetRegistry, leaf));
        }
    }

    private static int depth(String name) {
        int dots = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '.') dots++;
        return dots;
    }
}
