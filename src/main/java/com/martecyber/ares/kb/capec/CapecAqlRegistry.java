package com.martecyber.ares.kb.capec;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.kb.attack.AttackTechnique;
import com.martecyber.ares.kb.cwe.CweEntry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for CapecEntry (Postgres, {@code ares.capec} — AQL-wide initiative, Phase 5, migrated
 * off MongoDB) — used both for direct CAPEC queries and as the resolution target for
 * Detection/Finding's {@code capec.*} {@link com.martecyber.ares.aql.registry.RelationAqlField}.
 * Field names preserved exactly from the pre-migration Mongo-backed registry, plus new
 * HAS-enabled array fields that only became queryable now that they're native Postgres arrays.
 */
@Component
public class CapecAqlRegistry implements EntityAqlRegistry<CapecEntry> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> ARRAY_OPS = EnumSet.of(AqlOperator.HAS);

    private final Map<String, AqlField<CapecEntry>> fields = new LinkedHashMap<>();
    private final List<AqlField<CapecEntry>> defaultSearchFields;

    public CapecAqlRegistry() {
        // Backed by CapecEntry.code ("CAPEC-63"), not the bare-numeric capecId ("63") — same
        // reasoning as CweAqlRegistry's "id" field (see its own doc comment): matches how every
        // official CAPEC code is written and how CWE/CVE/ATT&CK already expose their own "id".
        // parentCapecs/childCapecs and CweAqlRegistry.relatedCapecs stay correlated against the
        // bare capecId below — parent_capec_ids/child_capec_ids/cwe.related_capec_ids all store
        // bare numeric ids, not the prefixed code.
        ColumnAqlField<CapecEntry> capecId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("code"));
        ColumnAqlField<CapecEntry> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(capecId);
        register(name);
        register(column("abstraction", AqlFieldType.STRING, STRING_OPS, r -> r.get("abstraction")));
        register(column("status", AqlFieldType.STRING, STRING_OPS, r -> r.get("status")));
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));
        register(column("typicalSeverity", AqlFieldType.STRING, STRING_OPS, r -> r.get("typicalSeverity")));
        register(column("likelihoodOfAttack", AqlFieldType.STRING, STRING_OPS, r -> r.get("likelihoodOfAttack")));
        register(column("syncedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("syncedAt")));
        register(new ArrayAqlField<>("prerequisites", ARRAY_OPS, r -> r.get("prerequisites")));
        register(new ArrayAqlField<>("mitigations", ARRAY_OPS, r -> r.get("mitigations")));

        // relatedCwes/relatedAttackTechniques/parentCapecs/childCapecs: list[cwe]/list[attack]/
        // list[capec]/list[capec] (were bare ArrayAqlField/HAS-only fields named relatedCweIds/
        // relatedAttackTechniqueIds/parentCapecIds/childCapecIds) — array-membership relations,
        // named after the entity they resolve to, same "cwes"/CweAqlRegistry.parents convention.
        // parentCapecs/childCapecs are self-referential (CAPEC -> CAPEC), safe from runaway
        // expansion thanks to RelationExpansion's own depth cap. The 4th/5th listOf args are real
        // JPA property names, unaffected by any AQL-surface renaming. relatedCwes correlates against
        // CweEntry.code (not cweId) — relatedCweIds stores the "cwe-79"-prefixed form.
        register(RelationAqlField.<CapecEntry, CweEntry>listOf("relatedCwes", "cwe", CweEntry.class, "relatedCweIds", "code"));
        register(RelationAqlField.<CapecEntry, AttackTechnique>listOf("relatedAttackTechniques", "attack", AttackTechnique.class, "relatedAttackTechniqueIds", "attackId"));
        register(RelationAqlField.<CapecEntry, CapecEntry>listOf("parentCapecs", "capec", CapecEntry.class, "parentCapecIds", "capecId"));
        register(RelationAqlField.<CapecEntry, CapecEntry>listOf("childCapecs", "capec", CapecEntry.class, "childCapecIds", "capecId"));
        register(new ArrayAqlField<>("domains", ARRAY_OPS, r -> r.get("domains")));

        this.defaultSearchFields = List.of(capecId, name);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<CapecEntry> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                               java.util.function.Function<jakarta.persistence.criteria.Root<CapecEntry>,
                                                   jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<CapecEntry> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "capec";
    }

    @Override
    public Optional<AqlField<CapecEntry>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<CapecEntry>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<CapecEntry>> allFields() {
        return List.copyOf(fields.values());
    }
}
