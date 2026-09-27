package com.martecyber.ares.kb.attack;

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
 * AQL fields for AttackTactic (Postgres, {@code ares.attack_tactic} — AQL-wide initiative, Phase
 * 5). Registered in {@link com.martecyber.ares.aql.AqlRegistryLookup} purely so {@link
 * AttackAqlRegistry}'s {@code tactics} {@link RelationAqlField} can resolve it by name — there is
 * no standalone REST {@code ?aql=} surface for this entity (tactics were never independently
 * queryable, same product decision already made for KEV), only reachable nested as
 * {@code attack.tactics.*}.
 */
@Component
public class AttackTacticAqlRegistry implements EntityAqlRegistry<AttackTactic> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);

    private final Map<String, AqlField<AttackTactic>> fields = new LinkedHashMap<>();
    private final List<AqlField<AttackTactic>> defaultSearchFields;

    public AttackTacticAqlRegistry() {
        ColumnAqlField<AttackTactic> attackId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("attackId"));
        ColumnAqlField<AttackTactic> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(attackId);
        register(name);
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));
        register(column("shortName", AqlFieldType.STRING, STRING_OPS, r -> r.get("shortName")));
        register(column("matrix", AqlFieldType.STRING, STRING_OPS, r -> r.get("matrix")));
        register(column("order", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("order")));

        this.defaultSearchFields = List.of(attackId, name);
    }

    private ColumnAqlField<AttackTactic> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                                 java.util.function.Function<jakarta.persistence.criteria.Root<AttackTactic>,
                                                     jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<AttackTactic> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "attackTactic";
    }

    @Override
    public Optional<AqlField<AttackTactic>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<AttackTactic>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<AttackTactic>> allFields() {
        return List.copyOf(fields.values());
    }
}
