package com.martecyber.ares.findings.templates;

import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldKind;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.ColumnAqlField;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;
import com.martecyber.ares.aql.registry.RelationAqlField;
import com.martecyber.ares.aql.registry.RelationExpansion;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AQL fields for FindingTemplate (Phase 4 — the UI search-bar rollout surfaced that this list had
 * no filtering at all, not even a "q" param). FindingTemplate is a platform-wide catalog entity
 * (no organizationId), unlike Finding/Detection — so unlike those, there's no scope predicate to
 * AND in on top of the compiled AQL spec. No dynamic fields yet: field_definition has no
 * entity_type='findingTemplate' rows (templates carry their own field values via
 * FindingTemplateField, a separate per-template EAV table, not the unified jsonb model) — adding
 * that unification is out of scope here, this registry just covers the entity's own columns.
 *
 * <p>"priority" is VIRTUAL, backed by {@link FindingTemplate#getPriority()}'s read-only formula
 * bridge (FindingTemplate has no real priority column, unlike Detection/Finding post-V144) —
 * PRIORITY-typed, only "P0".."P4" are valid values. No separate "severity" AQL field: same
 * locked-in decision as Detection/Finding — severity is derived, priority is the only queryable axis.
 */
@Component
public class FindingTemplateAqlRegistry implements EntityAqlRegistry<FindingTemplate> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);
    private static final Set<AqlOperator> DATE_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE, AqlOperator.IN);
    private static final Set<AqlOperator> NUMBER_OPS = DATE_OPS;
    private static final Set<AqlOperator> ID_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN);

    private final Map<String, AqlField<FindingTemplate>> fields = new LinkedHashMap<>();
    private final List<AqlField<FindingTemplate>> defaultSearchFields;

    public FindingTemplateAqlRegistry() {
        ColumnAqlField<FindingTemplate> title = column("title", AqlFieldType.STRING, STRING_OPS, r -> r.get("title"));

        register(title);
        register(new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.VIRTUAL, NUMBER_OPS,
            r -> r.get("priority"), AqlField.PRIORITY_LABELS));
        register(column("creatorId", AqlFieldType.NUMBER, ID_OPS, r -> r.get("creatorId")));
        register(column("createdAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("createdAt")));
        register(column("updatedAt", AqlFieldType.DATE, DATE_OPS, r -> r.get("updatedAt")));

        // tags.* — via the finding_template_tag join table (shared tag catalog,
        // com.martecyber.ares.tags). Only platform tags (no organization of their own) can ever be
        // assigned to a FindingTemplate — see FindingTemplateService.assignTag.
        register(new RelationAqlField<FindingTemplate, com.martecyber.ares.tags.Tag>(
            "tags", "tag", com.martecyber.ares.tags.Tag.class,
            (templateRoot, tagRoot, sub, cb) -> {
                var bridge = sub.from(FindingTemplateTag.class);
                return cb.and(cb.equal(bridge.get("findingTemplateId"), templateRoot.get("id")), cb.equal(bridge.get("tagId"), tagRoot.get("id")));
            }));

        this.defaultSearchFields = List.of(title);
    }

    @Override
    public void expandRelations(AqlRegistryLookup lookup) {
        RelationExpansion.expand(fields, lookup);
    }

    private ColumnAqlField<FindingTemplate> column(String name, AqlFieldType type, Set<AqlOperator> ops,
                                                     java.util.function.Function<jakarta.persistence.criteria.Root<FindingTemplate>,
                                                         jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, type, AqlFieldKind.PHYSICAL_COLUMN, ops, pathFn);
    }

    private void register(AqlField<FindingTemplate> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() {
        return "findingTemplate";
    }

    @Override
    public Optional<AqlField<FindingTemplate>> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public List<AqlField<FindingTemplate>> defaultSearchFields() {
        return defaultSearchFields;
    }

    @Override
    public List<AqlField<FindingTemplate>> allFields() {
        return List.copyOf(fields.values());
    }
}
