package com.martecyber.ares.detections;

import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields available for Detection. "status" still queries the free-string column (kept in
 * sync with the relational detection_status model by DetectionService, V143). "priority" queries
 * the real canonical priority column (V144) and is a {@code PRIORITY}-typed field — only "P0"
 * through "P4" are valid values (0=P0 most urgent .. 4=P4 least urgent), never a raw integer and
 * never the old severity vocabulary. There is deliberately no separate "severity" AQL field
 * anymore — severity is fully derived from priority now (locked-in decision from the AQL plan),
 * so the query surface only exposes the one canonical axis.
 *
 * <p>{@code cve.severity} below is unrelated to this — it's the *linked CVE's* own external
 * CVSS-derived severity classification (critical/high/medium/low, straight from NVD/KB data),
 * not Ares's own priority concept, so it stays a plain STRING field.
 *
 * <p>{@code cve.*} fields (Phase 2) close the plan's core cross-store requirement — "detections
 * whose linked CVE is KEV-listed" is {@code type == detection AND cve.kevListed == true}. The hot ones
 * (kevListed/cvssScore/severity/exploitCount) are KB_MATERIALIZED, compiling to a plain Postgres
 * EXISTS join against kb_materialized_ref (V145) with no per-query join to {@code ares.cve} needed;
 * everything else under {@code cve.} is a {@code RelationAqlField} straight against {@code ares.cve}.
 */
@Component
public class DetectionAqlRegistry implements EntityAqlRegistry<Detection> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = NUMBER_OPS;
    private static final Set<AqlOperator> ID_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);

    private static final String CVE_CATALOG_CODE = "CVE";
    private static final String REFERENCES_INVERSE_ATTRIBUTE = "detections";

    private final Map<String, AqlField<Detection>> fields = new LinkedHashMap<>();
    private final List<AqlField<Detection>> defaultSearchFields;

    public DetectionAqlRegistry(ReferenceCatalogRepository catalogRepo, DetectionStatusRepository statusRepo) {
        // title/description/status/priority/createdAt/updatedAt/lastSeen carry an in-memory value
        // resolver (Workflows implementation plan, Phase A) so a Detection-scoped Workflow
        // CONDITION node can evaluate against them without a DB round trip — the fields most
        // likely to appear in a "when this detection is created, if priority==P0..." condition.
        // Everything else here (sourceType/dedupHash/assetId/... and all cve.*/cwe.*/etc KB
        // fields) intentionally has none yet; AqlInMemoryEvaluator rejects those with a clear
        // error rather than guessing, and more resolvers can be added incrementally as workflows
        // actually need them.
        // Own primary key — lets a Workflow CONDITION/ASSIGN_VARIABLE source pin a specific
        // detection by id (e.g. "id == {{trigger.entityId}}"); see AssetAqlRegistry's own "id"
        // field for the same rationale (added after a real Workflow COUNT_COMPARE query on
        // "detection" hit AqlFieldNotFoundException for this exact field).
        register(column("id", AqlFieldType.NUMBER, ID_OPS, r -> r.get("id"), Detection::getId));

        ColumnAqlField<Detection> title = column("title", AqlFieldType.STRING, STRING_OPS, r -> r.get("title"), Detection::getTitle);
        ColumnAqlField<Detection> description = column("description", AqlFieldType.STRING, STRING_OPS, r -> r.get("description"), Detection::getDescription);

        register(title);
        register(description);
        List<String> statusNames = statusRepo.findAllByOrderByIdAsc().stream().map(DetectionStatus::getName).toList();
        register(new ColumnAqlField<>("status", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN, STRING_OPS,
            r -> r.get("status"), statusNames, Detection::getStatus));
        // status.<name> (dashboards remodel, Phase 13) — "when did this detection FIRST
        // transition into <name>", one StatusTransitionAqlField per known status name, matched
        // against DetectionStatusHistory.toStatus (a raw string, no join needed — unlike
        // Finding's normalized finding_status lookup table). See that class's own javadoc for why
        // this can't just be a RelationAqlField over detection_status_history.
        statusNames.forEach(statusName -> register(statusTransitionField(statusName)));
        register(column("sourceType", AqlFieldType.STRING, STRING_OPS, r -> r.get("sourceType")));
        register(column("sourceTemplateId", AqlFieldType.STRING, STRING_OPS, r -> r.get("sourceTemplateId")));
        register(column("dedupHash", AqlFieldType.STRING, STRING_OPS, r -> r.get("dedupHash")));
        register(column("assetId", AqlFieldType.NUMBER, ID_OPS, r -> r.get("assetId")));
        register(column("projectId", AqlFieldType.NUMBER, ID_OPS, r -> r.get("projectId")));
        register(column("occurrenceCount", AqlFieldType.NUMBER, NUMBER_OPS, r -> r.get("occurrenceCount")));
        register(column("createdAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("createdAt"), Detection::getCreatedAt));
        register(column("updatedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("updatedAt"), Detection::getUpdatedAt));
        register(column("lastSeen", AqlFieldType.DATE, DATE_OPS, r -> r.get("lastSeen"), Detection::getLastSeen));
        register(new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.PHYSICAL_COLUMN, NUMBER_OPS,
            r -> r.get("priority"), AqlField.PRIORITY_LABELS, Detection::getPriority));

        // cve.* (Phase 3 of the AQL-wide initiative: CVE moved to Postgres) — a RelationAqlField
        // through the same ReferenceEntry/reference_entry_detection bridge KbMaterializedField
        // used to join through; only what's on the other side changed (a real ares.cve row
        // instead of kb_materialized_ref/Mongo). Every "cve.<leaf>" sub-field name is now produced
        // automatically by AqlRegistryLookup's flat-expansion pass from CveAqlRegistry's own field
        // list — no more hand-listing each one here, which is exactly the duplication that let
        // "cve.cveId" go missing from this registry in the first place (the bug that started this
        // whole initiative).
        catalogRepo.findByCode(CVE_CATALOG_CODE).ifPresent(catalog -> {
            Long catalogId = catalog.getId();
            register(new RelationAqlField<Detection, com.martecyber.ares.kb.cve.CveEntry>(
                "cve", "cve", com.martecyber.ares.kb.cve.CveEntry.class,
                (detectionRoot, cveRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, detectionRoot),
                        cb.equal(refRoot.get("catalogId"), catalogId),
                        cb.equal(refRoot.get("title"), cveRoot.get("cveId")));
                }));
        });

        // owasp.* (Phase 5 of the AQL-wide initiative: OWASP moved to Postgres) — a
        // RelationAqlField through the same ReferenceEntry/reference_entry_detection bridge
        // KbFederatedField used to join through; only what's on the other side changed (a real
        // ares.owasp row instead of Mongo). Every "owasp.<leaf>" sub-field name is now produced
        // automatically by AqlRegistryLookup's flat-expansion pass from OwaspAqlRegistry's own
        // field list. OWASP's natural key is (owaspId, year) — the same rank id recurs across
        // editions — but the reference bridge only ever stored the bare owaspId (no year), so this
        // correlates on owaspId alone, reproducing the exact same "any edition sharing that id"
        // semantics the old KB_FEDERATED path already had (it resolved matched codes the same way).
        catalogRepo.findByCode("OWASP").ifPresent(catalog -> {
            Long owaspCatalogId = catalog.getId();
            register(new RelationAqlField<Detection, com.martecyber.ares.kb.owasp.OwaspEntry>(
                "owasp", "owasp", com.martecyber.ares.kb.owasp.OwaspEntry.class,
                (detectionRoot, owaspRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, detectionRoot),
                        cb.equal(refRoot.get("catalogId"), owaspCatalogId),
                        cb.equal(refRoot.get("title"), owaspRoot.get("owaspId")));
                }));
        });

        // cwe.* (Phase 5 of the AQL-wide initiative: CWE moved to Postgres) — same RelationAqlField
        // treatment as owasp.* above, correlated on the plain cweId (bare numeric id, no "CWE-"
        // prefix — matches ReferenceEntry.title's stored shape, same as before migration).
        catalogRepo.findByCode("CWE").ifPresent(catalog -> {
            Long cweCatalogId = catalog.getId();
            register(new RelationAqlField<Detection, com.martecyber.ares.kb.cwe.CweEntry>(
                "cwe", "cwe", com.martecyber.ares.kb.cwe.CweEntry.class,
                (detectionRoot, cweRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, detectionRoot),
                        cb.equal(refRoot.get("catalogId"), cweCatalogId),
                        cb.equal(refRoot.get("title"), cweRoot.get("cweId")));
                }));
        });

        // capec.* (Phase 5 of the AQL-wide initiative: CAPEC moved to Postgres) — same
        // RelationAqlField treatment as cwe.* above, correlated on the plain capecId.
        catalogRepo.findByCode("CAPEC").ifPresent(catalog -> {
            Long capecCatalogId = catalog.getId();
            register(new RelationAqlField<Detection, com.martecyber.ares.kb.capec.CapecEntry>(
                "capec", "capec", com.martecyber.ares.kb.capec.CapecEntry.class,
                (detectionRoot, capecRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, detectionRoot),
                        cb.equal(refRoot.get("catalogId"), capecCatalogId),
                        cb.equal(refRoot.get("title"), capecRoot.get("capecId")));
                }));
        });

        // attack.* (Phase 5 of the AQL-wide initiative: ATT&CK moved to Postgres, final entity of
        // this phase) — same RelationAqlField treatment as cwe.*/capec.* above, correlated on the
        // plain attackId (e.g. "T1059"). No auto-extraction regex exists for ATT&CK references yet
        // (unlike CVE/CWE), so this resolves against zero linked references until either a
        // reference gets attached manually or the extractor grows a matching regex — architecture
        // is complete regardless, this is just the query surface. Via AqlRegistryLookup's
        // flat-expansion, this also transitively exposes attack.tactics.*/attack.mitigations.*
        // (AttackAqlRegistry's own new relations) for free.
        catalogRepo.findByCode("ATT&CK").ifPresent(catalog -> {
            Long attackCatalogId = catalog.getId();
            register(new RelationAqlField<Detection, com.martecyber.ares.kb.attack.AttackTechnique>(
                "attack", "attack", com.martecyber.ares.kb.attack.AttackTechnique.class,
                (detectionRoot, attackRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, detectionRoot),
                        cb.equal(refRoot.get("catalogId"), attackCatalogId),
                        cb.equal(refRoot.get("title"), attackRoot.get("attackId")));
                }));
        });

        // asset.* (Phase 2, relational nesting) — one-hop forward: Detection.assetId is a plain
        // scalar FK, not a JPA relationship object, so the correlation is a direct id comparison.
        register(new RelationAqlField<Detection, com.martecyber.ares.assets.Asset>(
            "asset", "asset", com.martecyber.ares.assets.Asset.class,
            (detectionRoot, assetRoot, sub, cb) -> cb.equal(assetRoot.get("id"), detectionRoot.get("assetId"))));

        // findings.* (Phase 2, relational nesting) — two-hop via Affection: there's no direct FK
        // from Detection to Finding, only through Affection.detections (@ManyToMany) correlated
        // back to the outer Detection, joined to Affection.findingId (a plain scalar FK, not a
        // relationship object) to reach Finding.
        register(new RelationAqlField<Detection, com.martecyber.ares.findings.Finding>(
            "findings", "finding", com.martecyber.ares.findings.Finding.class,
            (detectionRoot, findingRoot, sub, cb) -> {
                var bridge = sub.from(Affection.class);
                var joined = bridge.join("detections");
                return cb.and(cb.equal(joined, detectionRoot), cb.equal(bridge.get("findingId"), findingRoot.get("id")));
            }));

        // tags.* — via the detection_tag join table (shared tag catalog, com.martecyber.ares.tags).
        register(new RelationAqlField<Detection, com.martecyber.ares.tags.Tag>(
            "tags", "tag", com.martecyber.ares.tags.Tag.class,
            (detectionRoot, tagRoot, sub, cb) -> {
                var bridge = sub.from(DetectionTag.class);
                return cb.and(cb.equal(bridge.get("detectionId"), detectionRoot.get("id")), cb.equal(bridge.get("tagId"), tagRoot.get("id")));
            }));

        this.defaultSearchFields = List.of(title, description);
    }

    /** {@code MIN(changed_at)} over {@code detection_status_history} rows correlated to the outer
     *  Detection and matching {@code to_status = statusName} — no join needed, unlike Finding's
     *  normalized status table: {@code DetectionStatusHistory.toStatus} is a plain string column. */
    private static StatusTransitionAqlField<Detection> statusTransitionField(String statusName) {
        return new StatusTransitionAqlField<>("status." + statusName, statusName, (detectionRoot, query, cb, name) -> {
            jakarta.persistence.criteria.Subquery<OffsetDateTime> sub = query.subquery(OffsetDateTime.class);
            var histRoot = sub.from(DetectionStatusHistory.class);
            var correlate = cb.equal(histRoot.get("detectionId"), detectionRoot.get("id"));
            var statusMatch = cb.equal(histRoot.get("toStatus"), name);
            sub.select(cb.least(histRoot.<OffsetDateTime>get("changedAt")));
            sub.where(cb.and(correlate, statusMatch));
            return sub;
        });
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<Detection> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                              java.util.function.Function<jakarta.persistence.criteria.Root<Detection>,
                                                  jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private ColumnAqlField<Detection> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                              java.util.function.Function<jakarta.persistence.criteria.Root<Detection>,
                                                  jakarta.persistence.criteria.Path<?>> pathFn,
                                              java.util.function.Function<Detection, Object> valueResolver) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn, List.of(), valueResolver);
    }

    private void register(AqlField<Detection> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "detection";
    }

    @Override
    public Optional<AqlField<Detection>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<Detection>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<Detection>> allFields() {
        return List.copyOf(fields.values());
    }
}
