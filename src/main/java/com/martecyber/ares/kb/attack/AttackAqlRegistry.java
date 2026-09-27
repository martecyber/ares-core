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
 * AQL fields for AttackTechnique (Postgres, {@code ares.attack_technique} — AQL-wide initiative,
 * Phase 5, migrated off MongoDB) — used both for direct ATT&amp;CK technique queries and as the
 * resolution target for Detection/Finding's {@code attack.*} {@link RelationAqlField}. Tactics and
 * mitigations (separate Postgres tables in this package) aren't direct top-level entities, but are
 * reachable nested here — {@code attack.tactics.*} and {@code attack.mitigations.*} — via new
 * relations onto the real {@code attack_technique_tactic}/{@code attack_technique_mitigation} join
 * tables (this phase's confirmed-in-scope work: {@link AttackStixParser} now parses the STIX
 * "mitigates" relationship objects it previously discarded entirely).
 *
 * <p>{@code tactics}/{@code platforms}/{@code dataSources}/{@code permissionsRequired} ARE
 * ARRAY_COLUMN/HAS-queryable, same as every other migrated array in this initiative — even though,
 * unlike those, these are NOT lowercase-normalized at write time (see AttackTechnique's own doc
 * comment) and must keep their exact original case because ares-ui renders them directly as
 * display strings (KbAttackTechniqueDetailView.vue's platform/tactic badges, KbAttackView.vue's
 * tactic-shortname matching). This is safe because {@code PostgresSpecificationCompiler}'s HAS
 * predicate ({@code ares.array_contains_ci}, V157) compares case-insensitively via {@code
 * LOWER(...)} on both sides at query time regardless of how the array itself is stored — see
 * {@link com.martecyber.ares.aql.registry.ArrayAqlField}'s own doc comment.
 */
@Component
public class AttackAqlRegistry implements EntityAqlRegistry<AttackTechnique> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final Set<AqlOperator> ARRAY_OPS = EnumSet.of(AqlOperator.HAS);

    private final Map<String, AqlField<AttackTechnique>> fields = new LinkedHashMap<>();
    private final List<AqlField<AttackTechnique>> defaultSearchFields;

    public AttackAqlRegistry() {
        ColumnAqlField<AttackTechnique> attackId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("attackId"));
        ColumnAqlField<AttackTechnique> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(attackId);
        register(name);
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));
        register(column("matrix", AqlFieldType.STRING, STRING_OPS, r -> r.get("matrix")));
        register(column("subtechnique", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("subtechnique")));
        register(column("deprecated", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("deprecated")));
        register(column("revoked", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("revoked")));

        register(new ArrayAqlField<>("platforms", ARRAY_OPS, r -> r.get("platforms")));
        register(new ArrayAqlField<>("dataSources", ARRAY_OPS, r -> r.get("dataSources")));
        register(new ArrayAqlField<>("permissionsRequired", ARRAY_OPS, r -> r.get("permissionsRequired")));

        // tactics.* — technique -> tactic via the new attack_technique_tactic join table.
        register(new RelationAqlField<AttackTechnique, AttackTactic>(
            "tactics", "attackTactic", AttackTactic.class,
            (techniqueRoot, tacticRoot, sub, cb) -> {
                var bridge = sub.from(AttackTechniqueTactic.class);
                return cb.and(
                    cb.equal(bridge.get("techniqueId"), techniqueRoot.get("id")),
                    cb.equal(bridge.get("tacticId"), tacticRoot.get("id")));
            }));

        // mitigations.* (Phase 5's confirmed-in-scope new STIX relationship parsing) — technique
        // -> mitigation via the new attack_technique_mitigation join table.
        register(new RelationAqlField<AttackTechnique, AttackMitigation>(
            "mitigations", "attackMitigation", AttackMitigation.class,
            (techniqueRoot, mitigationRoot, sub, cb) -> {
                var bridge = sub.from(AttackTechniqueMitigation.class);
                return cb.and(
                    cb.equal(bridge.get("techniqueId"), techniqueRoot.get("id")),
                    cb.equal(bridge.get("mitigationId"), mitigationRoot.get("id")));
            }));

        this.defaultSearchFields = List.of(attackId, name);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<AttackTechnique> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                                     java.util.function.Function<jakarta.persistence.criteria.Root<AttackTechnique>,
                                                         jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<AttackTechnique> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "attack";
    }

    @Override
    public Optional<AqlField<AttackTechnique>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<AttackTechnique>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<AttackTechnique>> allFields() {
        return List.copyOf(fields.values());
    }
}
