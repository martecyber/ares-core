package com.martecyber.ares.kb.attack;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for AttackMitigation (Postgres, {@code ares.attack_mitigation} — AQL-wide
 * initiative, Phase 5). Registered in {@link AqlRegistryLookup} so {@link AttackAqlRegistry}'s
 * {@code mitigations} {@link RelationAqlField} can resolve it; also exposes the reverse relation
 * {@code techniques} (mitigation -&gt; the techniques it mitigates), so {@code
 * mitigation.techniques.*} works the same way {@code attack.mitigations.*} does — same STIX
 * "mitigates" relationship, same {@code attack_technique_mitigation} bridge table, just correlated
 * from the other side. Like {@link AttackTacticAqlRegistry}, no standalone REST {@code ?aql=}
 * surface exists for this entity (mitigations, like tactics, were never independently queryable).
 */
@Component
public class AttackMitigationAqlRegistry implements EntityAqlRegistry<AttackMitigation> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);

    private final Map<String, AqlField<AttackMitigation>> fields = new LinkedHashMap<>();
    private final List<AqlField<AttackMitigation>> defaultSearchFields;

    public AttackMitigationAqlRegistry() {
        ColumnAqlField<AttackMitigation> attackId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("attackId"));
        ColumnAqlField<AttackMitigation> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(attackId);
        register(name);
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));
        register(column("matrix", AqlFieldType.STRING, STRING_OPS, r -> r.get("matrix")));
        register(column("deprecated", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("deprecated")));

        // techniques.* — reverse of AttackAqlRegistry's "mitigations" relation, same
        // attack_technique_mitigation bridge, correlated the other way round.
        register(new RelationAqlField<AttackMitigation, AttackTechnique>(
            "techniques", "attack", AttackTechnique.class,
            (mitigationRoot, techniqueRoot, sub, cb) -> {
                var bridge = sub.from(AttackTechniqueMitigation.class);
                return cb.and(
                    cb.equal(bridge.get("mitigationId"), mitigationRoot.get("id")),
                    cb.equal(bridge.get("techniqueId"), techniqueRoot.get("id")));
            }));

        this.defaultSearchFields = List.of(attackId, name);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<AttackMitigation> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                                      java.util.function.Function<jakarta.persistence.criteria.Root<AttackMitigation>,
                                                          jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<AttackMitigation> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "attackMitigation";
    }

    @Override
    public Optional<AqlField<AttackMitigation>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<AttackMitigation>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<AttackMitigation>> allFields() {
        return List.copyOf(fields.values());
    }
}
