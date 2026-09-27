package com.martecyber.ares.aql.registry;

/** Thrown when an AQL query references a field name that doesn't exist in the target entity's registry — mapped to a 400, never silently ignored (unlike the existing sort-field whitelist convention). */
public class AqlFieldNotFoundException extends RuntimeException {
    public AqlFieldNotFoundException(String entityName, String fieldName) {
        super("Unknown AQL field '" + fieldName + "' for entity '" + entityName + "'");
    }
}
