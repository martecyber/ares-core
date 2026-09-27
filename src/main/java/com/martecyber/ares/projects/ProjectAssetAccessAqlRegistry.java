package com.martecyber.ares.projects;

import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldKind;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.ColumnAqlField;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for {@link ProjectAssetAccess} — not queried directly, only ever reached as the
 * target of Asset's {@code scope} {@link com.martecyber.ares.aql.registry.RelationAqlField} (e.g.
 * {@code scope.status == "in_scope"}), the same role {@link com.martecyber.ares.tags.TagAqlRegistry}
 * plays for {@code tags.*}. Registered as its own bean purely so {@code RelationExpansion} has a
 * "projectAssetAccess" entity to flatten that relation against — see {@link
 * com.martecyber.ares.assets.AssetAqlRegistry}'s own {@code scope} field for why this only ever
 * resolves within a project-scoped query (it needs a request-scoped project id the registry
 * itself, a startup singleton, has no way to know otherwise — see {@code AssetAqlProjectContext}).
 */
@Component
public class ProjectAssetAccessAqlRegistry implements EntityAqlRegistry<ProjectAssetAccess> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);

    private final Map<String, AqlField<ProjectAssetAccess>> fields = new LinkedHashMap<>();

    public ProjectAssetAccessAqlRegistry() {
        // Same STRING+allowedValues shape AssetAqlRegistry already uses for its own "type"/
        // "hostSubtype" enum-like fields, not AqlFieldType.ENUM — kept consistent across the
        // codebase (the compiler treats both identically anyway).
        register(new ColumnAqlField<>("status", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN, STRING_OPS,
            r -> r.get("scopeStatus"),
            List.of(AssetScopeClassifier.IN_SCOPE, AssetScopeClassifier.OUT_OF_SCOPE,
                AssetScopeClassifier.INDETERMINATE, AssetScopeClassifier.THIRD_PARTY)));
        register(new ColumnAqlField<>("override", AqlFieldType.BOOLEAN, AqlFieldKind.PHYSICAL_COLUMN, BOOLEAN_OPS,
            r -> r.get("scopeOverride")));
    }

    private void register(AqlField<ProjectAssetAccess> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() { return "projectAssetAccess"; }

    @Override
    public Optional<AqlField<ProjectAssetAccess>> field(String name) { return Optional.ofNullable(fields.get(name)); }

    @Override
    public List<AqlField<ProjectAssetAccess>> defaultSearchFields() { return List.of(); }

    @Override
    public List<AqlField<ProjectAssetAccess>> allFields() { return List.copyOf(fields.values()); }
}
