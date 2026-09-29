package com.martecyber.ares.kb.owasp;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.kb.cwe.CweEntry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for OwaspEntry (Postgres, {@code ares.owasp} — AQL-wide initiative, Phase 5, migrated
 * off MongoDB) — used both for direct OWASP Top 10 queries and as the resolution target for
 * Detection/Finding's {@code owasp.*} {@link com.martecyber.ares.aql.registry.RelationAqlField}.
 * Field names preserved exactly from the pre-migration Mongo-backed registry.
 */
@Component
public class OwaspAqlRegistry implements EntityAqlRegistry<OwaspEntry> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);

    private final Map<String, AqlField<OwaspEntry>> fields = new LinkedHashMap<>();
    private final List<AqlField<OwaspEntry>> defaultSearchFields;

    public OwaspAqlRegistry() {
        ColumnAqlField<OwaspEntry> owaspId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("owaspId"));
        ColumnAqlField<OwaspEntry> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(owaspId);
        register(name);
        register(column("year", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("year")));
        register(column("rank", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("rank")));
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));

        // cwe: list[cwe] (was a bare ArrayAqlField/HAS-only field) — array-membership relation,
        // same "cwe" treatment as CveAqlRegistry (singular, not "cwes" — see its own comment for
        // why), correlated against CweEntry.code (not cweId) for the same reason: owasp.cwes
        // stores the "cwe-79"-prefixed form.
        register(RelationAqlField.<OwaspEntry, CweEntry>listOf("cwe", "cwe", CweEntry.class, "cwes", "code"));

        this.defaultSearchFields = List.of(owaspId, name);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<OwaspEntry> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                               java.util.function.Function<jakarta.persistence.criteria.Root<OwaspEntry>,
                                                   jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<OwaspEntry> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "owasp";
    }

    @Override
    public Optional<AqlField<OwaspEntry>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<OwaspEntry>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<OwaspEntry>> allFields() {
        return List.copyOf(fields.values());
    }
}
