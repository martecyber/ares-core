package com.martecyber.ares.tags;

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
 * AQL fields for {@link Tag} — not queried directly (no controller exposes {@code ?entity=tag}),
 * only ever reached as the target of a {@code tags} {@link com.martecyber.ares.aql.registry.RelationAqlField}
 * on Asset/Detection/Finding/Exploit/FindingTemplate (e.g. {@code tags.name == "Critical"}).
 * Registered as its own bean purely so {@code RelationExpansion} has a "tag" entity to flatten
 * those relations against — same role {@code CweAqlRegistry} plays for {@code cve.cwes.*}.
 */
@Component
public class TagAqlRegistry implements EntityAqlRegistry<Tag> {

    private static final Set<AqlOperator> STRING_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.CONTAINS, AqlOperator.IN);

    private final Map<String, AqlField<Tag>> fields = new LinkedHashMap<>();
    private final List<AqlField<Tag>> defaultSearchFields;

    public TagAqlRegistry() {
        ColumnAqlField<Tag> name = column("name", r -> r.get("name"));
        register(name);
        register(column("color", r -> r.get("color")));
        this.defaultSearchFields = List.of(name);
    }

    private ColumnAqlField<Tag> column(String name, java.util.function.Function<jakarta.persistence.criteria.Root<Tag>,
            jakarta.persistence.criteria.Path<?>> pathFn) {
        return new ColumnAqlField<>(name, AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN, STRING_OPS, pathFn);
    }

    private void register(AqlField<Tag> field) {
        fields.put(field.name(), field);
    }

    @Override
    public String entityName() { return "tag"; }

    @Override
    public Optional<AqlField<Tag>> field(String name) { return Optional.ofNullable(fields.get(name)); }

    @Override
    public List<AqlField<Tag>> defaultSearchFields() { return defaultSearchFields; }

    @Override
    public List<AqlField<Tag>> allFields() { return List.copyOf(fields.values()); }
}
