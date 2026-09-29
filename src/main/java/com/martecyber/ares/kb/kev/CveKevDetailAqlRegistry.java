package com.martecyber.ares.kb.kev;

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
 * AQL fields for {@link CveKevDetail} (Postgres, {@code ares.cve_kev_detail} — AQL-wide
 * initiative, Phase 4). Registered in {@link com.martecyber.ares.aql.AqlRegistryLookup} purely so
 * {@link com.martecyber.ares.kb.cve.CveAqlRegistry}'s {@code kev} {@link RelationAqlField} can
 * resolve it by name — there is deliberately no direct REST {@code ?aql=} surface for this entity
 * (no {@code CveKevDetailController}), matching the explicit product decision that KEV is not
 * independently AQL-queryable, only reachable nested as {@code cve.kev.*}.
 */
@Component
public class CveKevDetailAqlRegistry implements EntityAqlRegistry<CveKevDetail> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final Set<AqlOperator> ARRAY_OPS = EnumSet.of(AqlOperator.HAS);

    private final Map<String, AqlField<CveKevDetail>> fields = new LinkedHashMap<>();
    private final List<AqlField<CveKevDetail>> defaultSearchFields;

    public CveKevDetailAqlRegistry() {
        ColumnAqlField<CveKevDetail> source = column("source", AqlFieldType.STRING, STRING_OPS, r -> r.get("source"));
        ColumnAqlField<CveKevDetail> vulnerabilityName = column("vulnerabilityName", AqlFieldType.STRING, STRING_OPS, r -> r.get("vulnerabilityName"));

        register(source);
        register(vulnerabilityName);
        register(column("vendorProject", AqlFieldType.STRING, STRING_OPS, r -> r.get("vendorProject")));
        register(column("product", AqlFieldType.STRING, STRING_OPS, r -> r.get("product")));
        register(column("requiredAction", AqlFieldType.STRING, STRING_OPS, r -> r.get("requiredAction")));
        register(column("dateAdded", AqlFieldType.DATE, DATE_OPS, r -> r.get("dateAdded")));
        register(column("dueDate", AqlFieldType.DATE, DATE_OPS, r -> r.get("dueDate")));
        register(column("knownRansomwareCampaignUse", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("knownRansomwareCampaignUse")));
        register(column("reportedExploitedByCanaries", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("reportedExploitedByCanaries")));

        // cwe: list[cwe] (was a bare ArrayAqlField/HAS-only field) — array-membership relation,
        // same "cwe" treatment as CveAqlRegistry (singular, not "cwes" — see its own comment for
        // why), correlated against CweEntry.code (not cweId) for the same reason: kev's cwes
        // stores the "cwe-79"-prefixed form.
        register(RelationAqlField.<CveKevDetail, CweEntry>listOf("cwe", "cwe", CweEntry.class, "cwes", "code"));
        register(new ArrayAqlField<>("xdbUrls", ARRAY_OPS, r -> r.get("xdbUrls")));
        register(new ArrayAqlField<>("reportedExploitationUrls", ARRAY_OPS, r -> r.get("reportedExploitationUrls")));

        this.defaultSearchFields = List.of(vulnerabilityName);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<CveKevDetail> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                                 java.util.function.Function<jakarta.persistence.criteria.Root<CveKevDetail>,
                                                     jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<CveKevDetail> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "cveKevDetail";
    }

    @Override
    public Optional<AqlField<CveKevDetail>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<CveKevDetail>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<CveKevDetail>> allFields() {
        return List.copyOf(fields.values());
    }
}
