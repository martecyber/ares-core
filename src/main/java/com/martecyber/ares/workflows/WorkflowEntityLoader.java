package com.martecyber.ares.workflows;

import java.util.Optional;

/** Loads a live entity instance for a Workflow CONDITION node to evaluate against, by the AQL
 *  entity name (matches {@link com.martecyber.ares.aql.AqlRegistryLookup}'s own entityName key)
 *  plus its id. One Spring bean per supported entity type — {@link WorkflowEntityLookup}
 *  auto-discovers them the same way {@code AqlRegistryLookup} auto-discovers registries. One
 *  bean per entity a CONDITION node might realistically bind to (Detection/Finding/Asset today). */
public interface WorkflowEntityLoader<T> {
    String entityType();
    Optional<T> load(Long id);
}
