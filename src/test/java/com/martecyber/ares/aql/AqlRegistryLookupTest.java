package com.martecyber.ares.aql;

import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test (no Spring, no DB) for the multi-round relation-expansion pass added in Phase 4
 * of the AQL-wide initiative (see AqlRegistryLookup's own doc comment). Uses three tiny synthetic
 * registries — A has a relation to B, B itself has a relation to C — to prove a two-hop chain
 * ("a.b.c.value") resolves regardless of which order the registries are constructed/passed in,
 * which is the real bug this fix addresses: {@code registries.forEach(...)} iterates in whatever
 * order the caller's list happens to be in, not a topological one (impossible in general anyway,
 * since most real relations in this codebase are mutual/bidirectional).
 */
class AqlRegistryLookupTest {

    /** Marker type — these fixtures never touch a real entity, so any class works as T/R. */
    private record Thing(String id) {}

    private static class LeafRegistry implements EntityAqlRegistry<Thing> {
        private final String name;
        private final Map<String, AqlField<Thing>> fields = new LinkedHashMap<>();

        LeafRegistry(String name) {
            this.name = name;
            fields.put("value", new ColumnAqlField<>("value", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN,
                Set.of(AqlOperator.EQ), (java.util.function.Function<Root<Thing>, jakarta.persistence.criteria.Path<?>>)
                    r -> r.get("value")));
        }

        @Override public String entityName() { return name; }
        @Override public Optional<AqlField<Thing>> field(String n) { return Optional.ofNullable(fields.get(n)); }
        @Override public List<AqlField<Thing>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Thing>> allFields() { return List.copyOf(fields.values()); }
    }

    /** A registry with exactly one relation to another named registry — mirrors CveAqlRegistry's
     *  own "kev" relation shape (a bare RelationAqlField plus an expandRelations override). */
    private static class RelayRegistry implements EntityAqlRegistry<Thing> {
        private final String name;
        private final Map<String, AqlField<Thing>> fields = new LinkedHashMap<>();

        RelayRegistry(String name, String targetName) {
            this.name = name;
            fields.put(targetName, new RelationAqlField<Thing, Thing>(targetName, targetName, Thing.class,
                (outer, target, sub, cb) -> cb.conjunction()));
        }

        @Override public void expandRelations(AqlRegistryLookup lookup) { RelationExpansion.expand(fields, lookup); }
        @Override public String entityName() { return name; }
        @Override public Optional<AqlField<Thing>> field(String n) { return Optional.ofNullable(fields.get(n)); }
        @Override public List<AqlField<Thing>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Thing>> allFields() { return List.copyOf(fields.values()); }
    }

    @Test
    void twoHopChainResolvesWithRegistriesInDependencyOrder() {
        var c = new LeafRegistry("c");
        var b = new RelayRegistry("b", "c");
        var a = new RelayRegistry("a", "b");
        new AqlRegistryLookup(List.of(a, b, c));
        assertTrue(a.field("b.c.value").isPresent());
    }

    /** The actual regression case: with the ORIGINAL single-pass expansion, this list order (c,
     *  then b, then a) is the WORST case for a naive single forEach — a's "b.*" flattening would
     *  still see b's pre-expansion field list if a happened to run before b in the same pass. The
     *  multi-round fix means it doesn't matter which of these orderings the caller passes in. */
    @Test
    void twoHopChainResolvesRegardlessOfRegistryOrder() {
        var c1 = new LeafRegistry("c");
        var b1 = new RelayRegistry("b", "c");
        var a1 = new RelayRegistry("a", "b");
        new AqlRegistryLookup(List.of(c1, b1, a1));
        assertTrue(a1.field("b.c.value").isPresent());

        var c2 = new LeafRegistry("c");
        var b2 = new RelayRegistry("b", "c");
        var a2 = new RelayRegistry("a", "b");
        new AqlRegistryLookup(List.of(a2, c2, b2));
        assertTrue(a2.field("b.c.value").isPresent());
    }

    @Test
    void oneHopRelationStillWorksAsBefore() {
        var c = new LeafRegistry("c");
        var b = new RelayRegistry("b", "c");
        new AqlRegistryLookup(List.of(b, c));
        assertTrue(b.field("c.value").isPresent());
    }
}
