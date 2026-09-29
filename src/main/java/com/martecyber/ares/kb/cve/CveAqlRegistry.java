package com.martecyber.ares.kb.cve;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.kb.cwe.CweEntry;
import com.martecyber.ares.kb.kev.CveKevDetail;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for CveEntry (Postgres, {@code ares.cve} — AQL-wide initiative, Phase 3, migrated
 * off MongoDB) — used both for direct CVE queries (GET /api/v1/kb/cve?aql=...) and as the
 * resolution target for Detection/Finding's {@code cve.*} {@link
 * com.martecyber.ares.aql.registry.RelationAqlField}. Field names are preserved exactly from the
 * pre-migration Mongo-backed registry — no AQL-surface breaking change.
 */
@Component
public class CveAqlRegistry implements EntityAqlRegistry<CveEntry> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = NUMBER_OPS;
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);

    private final Map<String, AqlField<CveEntry>> fields = new LinkedHashMap<>();
    private final List<AqlField<CveEntry>> defaultSearchFields;

    public CveAqlRegistry() {
        ColumnAqlField<CveEntry> cveId = column("id", AqlFieldType.STRING, STRING_OPS, r -> r.get("cveId"));
        ColumnAqlField<CveEntry> description = column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description"));

        register(cveId);
        register(description);
        register(column("severity", AqlFieldType.STRING, STRING_OPS, r -> r.get("severity")));
        register(column("cvssScore", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("cvssScore")));
        // "kevListed" here means "listed in any KEV source" — resolves against the anyKevListed
        // column, kept under one AQL name regardless of the underlying denormalization detail.
        register(column("kevListed", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("anyKevListed")));
        register(column("exploitCount", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("exploitCount")));
        register(column("publishedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("publishedAt")));
        register(column("lastModifiedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("lastModifiedAt")));

        // cwe: list[cwe] (was a bare ArrayAqlField/HAS-only field) — array-membership relation
        // onto CweEntry, so "cwe.id == \"CWE-79\"" resolves the same CVEs "cwes HAS \"CWE-79\""
        // used to, plus reaches every other CWE field (cwe.name, cwe.abstraction, ...). See
        // RelationAqlField#listOf's own doc comment for why no new SQL/compiler path is needed.
        // Correlates against CweEntry.code ("CWE-79"), not the bare-numeric cweId ("79") — cve.cwes
        // already stores the "cwe-79"-prefixed form, and code is the CweEntry column that matches it.
        // AQL-facing name is singular ("cwe", not "cwes") for consistency across the whole registry
        // — cardinality doesn't drive plural/singular here, every catalog cross-reference does.
        register(RelationAqlField.<CveEntry, CweEntry>listOf("cwe", "cwe", CweEntry.class, "cwes", "code"));

        // kev.* (Phase 4 of the AQL-wide initiative) — nested detail from CveKevDetail, keyed by
        // plain cveId equality (CveKevDetail.cveId is a scalar column, not a JPA relationship, and
        // deliberately has no FK to ares.cve — see V151's migration comment). One CVE can have up
        // to two rows here (source='cisa' and/or source='vulncheck'), so ANDing several kev.*
        // conditions means "some kev row (possibly cisa for one condition, vulncheck for another)
        // satisfies each," same documented caveat as every other RelationAqlField in this
        // codebase — not "one single kev row satisfies all of them." KEV is deliberately NOT its
        // own standalone AQL-queryable entity (no ?aql= on CisaKevController/VulnCheckKevController,
        // no top-level "cveKevDetail" REST surface) — only reachable nested under cve.kev.*.
        register(new RelationAqlField<CveEntry, CveKevDetail>(
            "kev", "cveKevDetail", CveKevDetail.class,
            (cveRoot, kevRoot, sub, cb) -> cb.equal(kevRoot.get("cveId"), cveRoot.get("cveId"))));

        // affectedVendor/affectedProduct/affectedVersion — JSONB_ARRAY_MATCH against
        // affectedProductsJson (V185), the first AQL surface over CveEntry.affectedProducts (see
        // that field's own doc comment: previously deliberately kept out of AQL as "display-only").
        // EQ/NEQ only (JsonbArrayMatchField.supportedOperators()); combining more than one of these
        // in one AQL query has the same "independent per comparison, not necessarily the same
        // array element" caveat every other RELATION/KB_MATERIALIZED field here already documents
        // — see JsonbArrayMatchField's own doc comment.
        register(new JsonbArrayMatchField<>("affectedVendor", "affectedProductsJson",
            "ares.jsonb_array_field_contains_ci", List.of("vendor")));
        register(new JsonbArrayMatchField<>("affectedProduct", "affectedProductsJson",
            "ares.jsonb_array_field_contains_ci", List.of("product")));
        // affectedVersion == "1.2.3": resolves the queried version against every affected-product
        // element's defaultStatus + versions[] ranges (ares.cve_version_affected, V185) — true if
        // ANY element resolves to "affected" for that version, i.e. it's in an affected range and
        // not overridden by a later not-affected (fixed) range.
        register(new JsonbArrayMatchField<>("affectedVersion", "affectedProductsJson",
            "ares.cve_version_affected", List.of()));

        this.defaultSearchFields = List.of(cveId, description);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<CveEntry> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                             java.util.function.Function<jakarta.persistence.criteria.Root<CveEntry>,
                                                 jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<CveEntry> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "cve";
    }

    @Override
    public Optional<AqlField<CveEntry>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<CveEntry>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<CveEntry>> allFields() {
        return List.copyOf(fields.values());
    }
}
