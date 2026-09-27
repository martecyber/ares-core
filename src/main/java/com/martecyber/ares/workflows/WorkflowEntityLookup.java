package com.martecyber.ares.workflows;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Resolves an AQL entity name to its {@link WorkflowEntityLoader} — mirrors {@link
 *  com.martecyber.ares.aql.AqlRegistryLookup}'s own auto-discovery pattern exactly. */
@Component
public class WorkflowEntityLookup {

    private final Map<String, WorkflowEntityLoader<?>> byType;

    public WorkflowEntityLookup(List<WorkflowEntityLoader<?>> loaders) {
        this.byType = loaders.stream().collect(Collectors.toMap(WorkflowEntityLoader::entityType, l -> l));
    }

    @SuppressWarnings("unchecked")
    public Optional<Object> load(String entityType, Long id) {
        WorkflowEntityLoader<?> loader = byType.get(entityType);
        if (loader == null || id == null) return Optional.empty();
        return (Optional<Object>) loader.load(id);
    }
}
