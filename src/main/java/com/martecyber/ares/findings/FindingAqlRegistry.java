package com.martecyber.ares.findings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionAsset;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.detections.Detection;
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
 * AQL fields for Finding (Phase 3). "status" queries the read-only statusName @Formula (mirrors
 * Detection.severityWeight's precedent) rather than the raw statusId, so `status == open` reads
 * naturally. "priority" is the V144 canonical column, PRIORITY-typed — only "P0".."P4" are valid
 * values. There is no separate "severity" AQL field: severity is fully derived from priority now
 * (locked-in decision from the AQL plan), so the query surface only exposes the one canonical
 * axis, same as Detection's. {@code fields.*} are JSONB_PATH, one per field_definition row seeded
 * for entity_type='finding' (V141) — dynamically discovered at startup, not hardcoded, so a new
 * field type shows up in AQL as soon as it's added to the catalog.
 *
 * <p>{@code cve.*}/{@code cwe.*}/{@code capec.*}/{@code owasp.*}/{@code attack.*} are full parity with
 * Detection's own namespaces (same sub-field names, same {@code <namespace>Id} identifier field on each —
 * see the identically-named registries under {@code kb.*} for the standalone entities this mirrors), via
 * {@code ReferenceEntry.findings} (the {@code reference_entry_finding} join, populated by
 * {@code FindingService} whenever a finding is created/updated with explicit or template-copied
 * {@code referenceIds}) — no entity/migration changes needed for the join itself. {@code cve.kevListed}/
 * {@code cvssScore}/{@code severity}/{@code exploitCount} are {@code KbMaterializedField}s resolved from
 * the {@code kb_materialized_ref} fast-path cache; {@code FindingService.materializeIfCve} keeps that cache
 * populated for finding-linked CVEs the same way {@code DetectionReferenceExtractor} does for detections.
 */
@Component
public class FindingAqlRegistry implements EntityAqlRegistry<Finding> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = NUMBER_OPS;
    private static final Set<AqlOperator> ID_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CVE_CATALOG_CODE = "CVE";
    private static final String REFERENCES_INVERSE_ATTRIBUTE = "findings";

    private final Map<String, AqlField<Finding>> fields = new LinkedHashMap<>();
    private final List<AqlField<Finding>> defaultSearchFields;

    public FindingAqlRegistry(FieldDefinitionRepository fieldDefinitionRepo, FindingStatusRepository statusRepo,
                               ReferenceCatalogRepository referenceCatalogRepo) {
        // Own primary key — same rationale as AssetAqlRegistry/DetectionAqlRegistry's own "id"
        // field: lets a Workflow ASSIGN_VARIABLE/CONDITION source pin one specific finding by id.
        register(column("id", AqlFieldType.NUMBER, ID_OPS, r -> r.get("id")));

        ColumnAqlField<Finding> title = column("title", AqlFieldType.STRING, STRING_OPS, r -> r.get("title"));

        register(title);
        register(column("code", AqlFieldType.STRING, STRING_OPS, r -> r.get("code")));
        register(new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.PHYSICAL_COLUMN, NUMBER_OPS,
            r -> r.get("priority"), AqlField.PRIORITY_LABELS));
        List<String> statusNames = statusRepo.findAll().stream().map(FindingStatus::getName).toList();
        register(new ColumnAqlField<>("status", AqlFieldType.STRING, AqlFieldKind.VIRTUAL, STRING_OPS,
            r -> r.get("statusName"), statusNames));
        // status.<name> (dashboards remodel, Phase 13) — "when did this finding FIRST transition
        // into <name>", one StatusTransitionAqlField per known status name. See that class's own
        // javadoc for why this can't just be a RelationAqlField over finding_status_history.
        statusNames.forEach(statusName -> register(statusTransitionField(statusName)));
        register(column("isDraft", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("isDraft")));
        // Read-only, backed by Finding.open's @Formula (dashboards remodel) — remediation open
        // (no affections, or at least one still open), the same definition
        // FindingRepository.findOpenPublishedByOrgId used to compute in Java only.
        register(new ColumnAqlField<>("isOpen", AqlFieldType.BOOLEAN, AqlFieldKind.VIRTUAL, BOOLEAN_OPS, r -> r.get("open")));
        register(column("isReadyToReport", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("isReadyToReport")));
        register(column("iterationLabel", AqlFieldType.STRING, STRING_OPS, r -> r.get("iterationLabel")));
        register(column("projectId", AqlFieldType.NUMBER, ID_OPS, r -> r.get("projectId")));
        register(column("dueDate", AqlFieldType.DATE, DATE_OPS, r -> r.get("dueDate")));
        // Read-only, backed by Finding.slaDeadline's @Formula (dashboards remodel) — the same
        // due_date-or-computed-from-SLA-settings deadline OrganizationService.deadline() used to
        // only be reachable from Java, now directly queryable (e.g. `slaDeadline < now`).
        register(new ColumnAqlField<>("slaDeadline", AqlFieldType.DATE, AqlFieldKind.VIRTUAL, DATE_OPS, r -> r.get("slaDeadline")));
        register(column("reportedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("reportedAt")));
        register(column("resolvedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("resolvedAt")));
        register(column("createdAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("createdAt")));
        register(column("updatedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("updatedAt")));

        fieldDefinitionRepo.findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc("finding")
            .forEach(def -> register(new JsonbPathField<>(
                "fields." + def.getFieldKey(),
                mapDataType(def.getDataType()),
                dataTypeOperators(def.getDataType()),
                "fields",
                def.getFieldKey(),
                parseAllowedValues(def.getAllowedValues()))));

        // cve.* (Phase 3 of the AQL-wide initiative: CVE moved to Postgres) — RelationAqlField
        // through the same ReferenceEntry/reference_entry_finding bridge the old KB_MATERIALIZED/
        // federated-fallback fields joined through; see DetectionAqlRegistry's identical treatment
        // for the full rationale.
        referenceCatalogRepo.findByCode(CVE_CATALOG_CODE).ifPresent(catalog -> {
            Long catalogId = catalog.getId();
            register(new RelationAqlField<Finding, com.martecyber.ares.kb.cve.CveEntry>(
                "cve", "cve", com.martecyber.ares.kb.cve.CveEntry.class,
                (findingRoot, cveRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, findingRoot),
                        cb.equal(refRoot.get("catalogId"), catalogId),
                        cb.equal(refRoot.get("title"), cveRoot.get("cveId")));
                }));
        });

        // owasp.* (Phase 5 of the AQL-wide initiative: OWASP moved to Postgres) — same
        // RelationAqlField treatment as Detection's own owasp.* (see its identical block for the
        // (owaspId, year) natural-key correlation rationale).
        referenceCatalogRepo.findByCode("OWASP").ifPresent(catalog -> {
            Long owaspCatalogId = catalog.getId();
            register(new RelationAqlField<Finding, com.martecyber.ares.kb.owasp.OwaspEntry>(
                "owasp", "owasp", com.martecyber.ares.kb.owasp.OwaspEntry.class,
                (findingRoot, owaspRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, findingRoot),
                        cb.equal(refRoot.get("catalogId"), owaspCatalogId),
                        cb.equal(refRoot.get("title"), owaspRoot.get("owaspId")));
                }));
        });

        // cwe.* (Phase 5 of the AQL-wide initiative: CWE moved to Postgres) — same
        // RelationAqlField treatment as Detection's own cwe.*.
        referenceCatalogRepo.findByCode("CWE").ifPresent(catalog -> {
            Long cweCatalogId = catalog.getId();
            register(new RelationAqlField<Finding, com.martecyber.ares.kb.cwe.CweEntry>(
                "cwe", "cwe", com.martecyber.ares.kb.cwe.CweEntry.class,
                (findingRoot, cweRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, findingRoot),
                        cb.equal(refRoot.get("catalogId"), cweCatalogId),
                        cb.equal(refRoot.get("title"), cweRoot.get("cweId")));
                }));
        });

        // capec.* (Phase 5 of the AQL-wide initiative: CAPEC moved to Postgres) — same
        // RelationAqlField treatment as Detection's own capec.*.
        referenceCatalogRepo.findByCode("CAPEC").ifPresent(catalog -> {
            Long capecCatalogId = catalog.getId();
            register(new RelationAqlField<Finding, com.martecyber.ares.kb.capec.CapecEntry>(
                "capec", "capec", com.martecyber.ares.kb.capec.CapecEntry.class,
                (findingRoot, capecRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, findingRoot),
                        cb.equal(refRoot.get("catalogId"), capecCatalogId),
                        cb.equal(refRoot.get("title"), capecRoot.get("capecId")));
                }));
        });

        // attackTechnique.* (Phase 5 of the AQL-wide initiative: ATT&CK moved to Postgres, final
        // entity of this phase) — same RelationAqlField treatment as Detection's own
        // attackTechnique.*. Name is "attackTechnique", not "attack", matching AttackAqlRegistry's
        // own entityName().
        referenceCatalogRepo.findByCode("ATT&CK").ifPresent(catalog -> {
            Long attackCatalogId = catalog.getId();
            register(new RelationAqlField<Finding, com.martecyber.ares.kb.attack.AttackTechnique>(
                "attackTechnique", "attackTechnique", com.martecyber.ares.kb.attack.AttackTechnique.class,
                (findingRoot, attackRoot, sub, cb) -> {
                    var refRoot = sub.from(com.martecyber.ares.references.ReferenceEntry.class);
                    var backJoin = refRoot.join(REFERENCES_INVERSE_ATTRIBUTE);
                    return cb.and(
                        cb.equal(backJoin, findingRoot),
                        cb.equal(refRoot.get("catalogId"), attackCatalogId),
                        cb.equal(refRoot.get("title"), attackRoot.get("attackId")));
                }));
        });

        // detections.* (Phase 2, relational nesting) — two-hop via Affection: there's no direct FK
        // from Finding to Detection, only through Affection.findingId (a plain scalar FK, not a
        // relationship object) correlated back to the outer Finding, joined to Affection.detections
        // (@ManyToMany) to reach Detection.
        register(new RelationAqlField<Finding, Detection>(
            "detections", "detection", Detection.class,
            (findingRoot, detectionRoot, sub, cb) -> {
                var bridge = sub.from(Affection.class);
                var joined = bridge.join("detections");
                return cb.and(cb.equal(bridge.get("findingId"), findingRoot.get("id")), cb.equal(joined, detectionRoot));
            }));

        // affectedAssets.* / detectedAtAssets.* (Phase 2) — two-hop via AffectionAsset,
        // role-separated per the user's explicit choice (not a single any-role "assets"
        // namespace) — see AssetAqlRegistry's roleFilteredAssetToFindingJoin for the reverse
        // direction of this exact same join.
        register(new RelationAqlField<Finding, Asset>(
            "affectedAssets", "asset", Asset.class, roleFilteredFindingToAssetJoin("affects")));
        register(new RelationAqlField<Finding, Asset>(
            "detectedAtAssets", "asset", Asset.class, roleFilteredFindingToAssetJoin("detected_at")));

        // tags.* — via the finding_tag join table (shared tag catalog, com.martecyber.ares.tags).
        register(new RelationAqlField<Finding, com.martecyber.ares.tags.Tag>(
            "tags", "tag", com.martecyber.ares.tags.Tag.class,
            (findingRoot, tagRoot, sub, cb) -> {
                var bridge = sub.from(FindingTag.class);
                return cb.and(cb.equal(bridge.get("findingId"), findingRoot.get("id")), cb.equal(bridge.get("tagId"), tagRoot.get("id")));
            }));

        this.defaultSearchFields = List.of(title);
    }

    /** {@code MIN(changed_at)} over {@code finding_status_history} rows correlated to the outer
     *  Finding and matching {@code finding_status.name = statusName} — {@code
     *  FindingStatusHistory} has no {@code @ManyToOne} to {@code FindingStatus} (just a raw
     *  {@code findingStatusId} inside its {@code @EmbeddedId}), so the match is a second subquery
     *  root joined manually on id, same shape {@link #compileMaterialized}-style code elsewhere in
     *  this codebase already uses for FK-without-relationship columns. */
    private static StatusTransitionAqlField<Finding> statusTransitionField(String statusName) {
        return new StatusTransitionAqlField<>("status." + statusName, statusName, (findingRoot, query, cb, name) -> {
            jakarta.persistence.criteria.Subquery<OffsetDateTime> sub = query.subquery(OffsetDateTime.class);
            var histRoot = sub.from(FindingStatusHistory.class);
            var statusRoot = sub.from(FindingStatus.class);
            var correlate = cb.equal(histRoot.get("id").get("findingId"), findingRoot.get("id"));
            var statusMatch = cb.and(
                cb.equal(statusRoot.get("id"), histRoot.get("id").get("findingStatusId")),
                cb.equal(statusRoot.get("name"), name));
            sub.select(cb.least(histRoot.get("id").<OffsetDateTime>get("changedAt")));
            sub.where(cb.and(correlate, statusMatch));
            return sub;
        });
    }

    private static RelationAqlField.RelationCorrelation<Finding, Asset> roleFilteredFindingToAssetJoin(String role) {
        return (findingRoot, assetRoot, sub, cb) -> {
            var affection = sub.from(Affection.class);
            var affectionAsset = sub.from(AffectionAsset.class);
            return cb.and(
                cb.equal(affection.get("findingId"), findingRoot.get("id")),
                cb.equal(affectionAsset.get("affectionId"), affection.get("id")),
                cb.equal(affectionAsset.get("role"), role),
                cb.equal(affectionAsset.get("asset"), assetRoot));
        };
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private static List<String> parseAllowedValues(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static AqlFieldType mapDataType(String dataType) {
        return switch (dataType) {
            case "number" -> AqlFieldType.NUMBER;
            case "boolean" -> AqlFieldType.BOOLEAN;
            case "date" -> AqlFieldType.DATE;
            case "enum" -> AqlFieldType.ENUM;
            default -> AqlFieldType.STRING;
        };
    }

    private static Set<AqlOperator> dataTypeOperators(String dataType) {
        return switch (dataType) {
            case "number", "date" -> NUMBER_OPS;
            case "boolean" -> BOOLEAN_OPS;
            default -> STRING_OPS;
        };
    }

    private ColumnAqlField<Finding> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                            java.util.function.Function<jakarta.persistence.criteria.Root<Finding>,
                                                jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<Finding> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "finding";
    }

    @Override
    public Optional<AqlField<Finding>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<Finding>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<Finding>> allFields() {
        return List.copyOf(fields.values());
    }
}
