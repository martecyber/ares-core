package com.martecyber.ares.assets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * AQL fields for Asset (Phase 3). Every {@code field_definition} row seeded for
 * entity_type='asset' becomes a JSONB_PATH field — deliberately sparse right now (V142's
 * "interfaceType", V186's SERVICE port/protocol/product/version, per real-data audits that found
 * little else in actual use — see the AQL implementation plan); more show up automatically as
 * field_definition grows, no registry change needed.
 *
 * <p>A row scoped to one asset type ({@code asset_type} non-null) registers under TWO names: the
 * type-prefixed form ({@code "<assetType>.<fieldKey>"}, e.g. {@code service.port}) — the
 * convention every type-specific attribute should be queried through going forward — and the
 * original {@code "metadata.<fieldKey>"} form, kept solely so any AQL already written against it
 * (a saved dashboard widget, a Workflow condition) keeps working; new fields need only the
 * type-prefixed name. A row with no asset_type (applies to every type) only ever gets the
 * {@code metadata.*} form, since there's no single type to prefix it with. {@code hostSubtype} —
 * its own physical column, not metadata, see V100__asset_host_subtype.sql — gets the same
 * type-prefixed alias treatment by hand below ({@code host.subtype}, alongside the original
 * top-level {@code hostSubtype} name).
 */
@Component
public class AssetAqlRegistry implements EntityAqlRegistry<Asset> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = NUMBER_OPS;
    private static final Set<AqlOperator> ID_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, AqlField<Asset>> fields = new LinkedHashMap<>();
    private final List<AqlField<Asset>> defaultSearchFields;

    public AssetAqlRegistry(FieldDefinitionRepository fieldDefinitionRepo) {
        ColumnAqlField<Asset> identifier = column("identifier", AqlFieldType.STRING, STRING_OPS, r -> r.get("identifier"));
        ColumnAqlField<Asset> code = column("code", AqlFieldType.STRING, STRING_OPS, r -> r.get("code"));

        // Own primary key — lets a Workflow ASSIGN_VARIABLE/CONDITION source pin a specific asset by
        // id (e.g. "id == {{trigger.detection.assetId}}"), the narrow, project-scope-safe way to
        // reach "the asset a given detection points to" without needing the broader (and still
        // unimplemented — see the removed detections.* relation below) asset→detections join.
        register(column("id", AqlFieldType.NUMBER, ID_OPS, r -> r.get("id")));
        register(identifier);
        register(code);
        register(new ColumnAqlField<>("type", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN, STRING_OPS,
            r -> r.get("type"), AssetType.ALL.stream().sorted().toList()));
        ColumnAqlField<Asset> hostSubtype = new ColumnAqlField<>("hostSubtype", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN,
            STRING_OPS, r -> r.get("hostSubtype"), HostSubtype.ALL.stream().sorted().toList());
        register(hostSubtype);
        register(new ColumnAqlField<>("host.subtype", hostSubtype.type(), hostSubtype.kind(),
            hostSubtype.supportedOperators(), hostSubtype.pathResolver(), hostSubtype.allowedValues()));
        register(column("nameOverride", AqlFieldType.BOOLEAN, BOOLEAN_OPS, r -> r.get("nameOverride")));
        register(column("organizationId", AqlFieldType.NUMBER, ID_OPS, r -> r.get("organizationId")));
        register(column("createdAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("createdAt")));
        register(column("updatedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("updatedAt")));

        fieldDefinitionRepo.findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc("asset")
            .forEach(def -> {
                AqlFieldType type = mapDataType(def.getDataType());
                Set<AqlOperator> ops = dataTypeOperators(def.getDataType());
                List<String> allowed = parseAllowedValues(def.getAllowedValues());
                register(new JsonbPathField<>("metadata." + def.getFieldKey(), type, ops, "metadata", def.getFieldKey(), allowed));
                if (def.getAssetType() != null) {
                    register(new JsonbPathField<>(def.getAssetType() + "." + def.getFieldKey(), type, ops, "metadata", def.getFieldKey(), allowed));
                }
            });

        // detections.* / affectedByFindings.* / detectedAtFindings.* (Phase 2) were removed here
        // (security fix, user-reported) — Asset is organization-scoped only (no project_id column;
        // the same asset row can legitimately be referenced by Detections/Findings from several
        // DIFFERENT projects within the org), while Detection/Finding are strictly project-scoped
        // data. The relation's correlation had no project filter at all (just detection.assetId ==
        // asset.id), so it was reachable from the organization-level Assets AQL bar with NO
        // detection data at all supposed to be visible there, AND from a project-scoped Assets
        // query it could still match — and therefore leak the mere existence of — a detection
        // belonging to a completely different project that happens to share the same asset. A
        // correct reinstatement needs the correlation to also pin the target project (Detection/
        // Finding must additionally match whichever project the asset query itself is scoped to),
        // which the AQL registry/compiler have no per-request mechanism for yet (registries are
        // built once at startup, not per request) — flagged as follow-up work, not done here.

        // tags.* — via the asset_tag join table (shared tag catalog, com.martecyber.ares.tags).
        // No project-boundary concern like the removed relations above: Asset's own tags aren't
        // project-scoped data, they're the asset's own org-scoped metadata.
        register(new RelationAqlField<Asset, com.martecyber.ares.tags.Tag>(
            "tags", "tag", com.martecyber.ares.tags.Tag.class,
            (assetRoot, tagRoot, sub, cb) -> {
                var bridge = sub.from(AssetTag.class);
                return cb.and(cb.equal(bridge.get("assetId"), assetRoot.get("id")), cb.equal(bridge.get("tagId"), tagRoot.get("id")));
            }));

        // scope.* — project-level scope classification (in_scope/out_of_scope/indeterminate/
        // third_party, plus whether it's a manual override), via project_asset_access. Only ever
        // resolves within a project-scoped query (AssetService.listByAql/countByAql set {@link
        // AssetAqlProjectContext} right before executing the compiled query) — the exact
        // per-request project-pinning mechanism the removed detections/findings relations above
        // never had; that reinstatement is still its own separate follow-up (it needs the whole
        // relation back, not just this), this is scoped to scope.* only. Throws a clear
        // AqlCompileException (not a silent empty/wrong result) when queried outside a project —
        // e.g. the org-wide Assets view.
        register(new RelationAqlField<Asset, com.martecyber.ares.projects.ProjectAssetAccess>(
            "scope", "projectAssetAccess", com.martecyber.ares.projects.ProjectAssetAccess.class,
            (assetRoot, paaRoot, sub, cb) -> {
                Long projectId = AssetAqlProjectContext.currentProjectId();
                if (projectId == null) {
                    throw new com.martecyber.ares.aql.compile.AqlCompileException(
                        "'scope.*' can only be queried within a project — no project is in scope for this query");
                }
                return cb.and(
                    cb.equal(paaRoot.get("assetId"), assetRoot.get("id")),
                    cb.equal(paaRoot.get("projectId"), projectId));
            }));

        this.defaultSearchFields = List.of(identifier, code);
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

    private ColumnAqlField<Asset> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                          java.util.function.Function<jakarta.persistence.criteria.Root<Asset>,
                                              jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<Asset> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "asset";
    }

    @Override
    public Optional<AqlField<Asset>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<Asset>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<Asset>> allFields() {
        return List.copyOf(fields.values());
    }
}
