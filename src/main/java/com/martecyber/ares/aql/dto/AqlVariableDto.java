package com.martecyber.ares.aql.dto;

/** Mirrors the frontend's {@code AqlVariableRef} shape (ares-ui/src/utils/aqlAutocomplete.ts)
 *  exactly, so {@code GET /aql/variables} can be handed straight to {@code computeAqlSuggestions}
 *  with no reshaping on the frontend side. */
public record AqlVariableDto(String ref, String label, String type) {
}
