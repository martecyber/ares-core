package com.martecyber.ares.kb.cwe;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.kb.capec.CapecEntry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for CweEntry (Postgres, {@code ares.cwe} — AQL-wide initiative, Phase 5, migrated off
 * MongoDB) — used both for direct CWE queries and as the resolution target for Detection/Finding's
 * {@code cwe.*} {@link com.martecyber.ares.aql.registry.RelationAqlField}. Field names preserved
 * exactly from the pre-migration Mongo-backed registry, plus new HAS-enabled array fields
 * ({@code parentIds}/{@code childIds}/{@code relatedCapecIds}/{@code applicablePlatforms}/
 * {@code observedExamples}) that only became queryable now that they're native Postgres arrays.
 */
@Component
public class CweAqlRegistry implements EntityAqlRegistry<CweEntry> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> ARRAY_OPS = EnumSet.of(AqlOperator.HAS);

    private final Map<String, AqlField<CweEntry>> fields = new LinkedHashMap<>();
    private final List<AqlField<CweEntry>> defaultSearchFields;

    public CweAqlRegistry() {
        // AQL name "id", not "cweId" — CWE's own identifier field doesn't need to repeat what
        // entity it's on when read directly; nested access already reads as "cwe.id" (see
        // RelationAqlField#listOf's callers below), which is unambiguous on its own. Backed by
        // CweEntry.code ("CWE-79"), NOT the bare-numeric CweEntry.cweId ("79") — every array that
        // references a CWE from another entity (cve.cwes, capec.relatedCweIds, owasp.cwes,
        // cve_kev_detail.cwes) already stores the "cwe-79"-style prefixed form, and code is the one
        // column that already matches it for 100% of rows (populated since the original CWE parser
        // was written, unlike cweId). Self-referential parentIds/childIds/relatedCapecIds stay
        // correlated against the bare cweId/capecId columns below — those arrays store bare
        // numeric ids, not the prefixed code.
        ColumnAqlField<CweEntry> cweId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("code"));
        ColumnAqlField<CweEntry> name = column("name", AqlFieldType.STRING, STRING_OPS, r -> r.get("name"));

        register(cweId);
        register(name);
        register(column("type", AqlFieldType.STRING, STRING_OPS, r -> r.get("type")));
        register(column("abstraction", AqlFieldType.STRING, STRING_OPS, r -> r.get("abstraction")));
        register(column("status", AqlFieldType.STRING, STRING_OPS, r -> r.get("status")));
        register(column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description")));
        register(column("likelihoodOfExploit", AqlFieldType.STRING, STRING_OPS, r -> r.get("likelihoodOfExploit")));
        register(column("syncedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("syncedAt")));

        // parents/children/relatedCapec: list[cwe]/list[cwe]/list[capec] (were bare
        // ArrayAqlField/HAS-only fields named parentIds/childIds/relatedCapecIds) — array-membership
        // relations, named after the ENTITY they resolve to (singular, same "cwe" treatment as
        // CveAqlRegistry — see its own comment for why) rather than the raw ID list they're backed
        // by (list[X] fields read as "a list of X", not "a list of X's ids" — the leaf you actually
        // compare against is "<field>.id", not the field name itself). parents/children are
        // role-based names, not the bare entity name, so they're left plural (a CWE genuinely has
        // multiple parents/children) — only relatedCapec(s) falls under the singular-catalog-name
        // convention. parents/children are also genuinely self-referential (CWE -> CWE); safe from
        // runaway expansion thanks to RelationExpansion's own depth cap (MAX_RELATION_DEPTH). The
        // 4th/5th listOf args are real JPA property names (CweEntry.parentIds, CweEntry.cweId) —
        // unaffected by any AQL-surface renaming, only the first arg (the AQL name) changes.
        register(RelationAqlField.<CweEntry, CweEntry>listOf("parents", "cwe", CweEntry.class, "parentIds", "cweId"));
        register(RelationAqlField.<CweEntry, CweEntry>listOf("children", "cwe", CweEntry.class, "childIds", "cweId"));
        register(RelationAqlField.<CweEntry, CapecEntry>listOf("relatedCapec", "capec", CapecEntry.class, "relatedCapecIds", "capecId"));
        register(new ArrayAqlField<>("applicablePlatforms", ARRAY_OPS, r -> r.get("applicablePlatforms")));
        register(new ArrayAqlField<>("observedExamples", ARRAY_OPS, r -> r.get("observedExamples")));

        this.defaultSearchFields = List.of(cweId, name);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<CweEntry> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                             java.util.function.Function<jakarta.persistence.criteria.Root<CweEntry>,
                                                 jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<CweEntry> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "cwe";
    }

    @Override
    public Optional<AqlField<CweEntry>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<CweEntry>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<CweEntry>> allFields() {
        return List.copyOf(fields.values());
    }
}
