package com.martecyber.ares.findings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads/writes {@link Finding#getFields()} — a jsonb map of finding_field_type.name -> field
 * text, replacing the old finding_field EAV table (AQL implementation plan, V141). Shared by
 * every call site that used to query FindingFieldRepository directly (FindingService,
 * FindingTemplateService, ReportGenerationService, MarkdownMigrationService) so the JSON shape
 * and error handling live in exactly one place.
 */
public final class FindingFields {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FindingFields() { }

    public static Map<String, String> read(Finding f) {
        String json = f.getFields();
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt finding.fields JSON for finding " + f.getId(), e);
        }
    }

    public static void write(Finding f, Map<String, String> fields) {
        try {
            f.setFields(MAPPER.writeValueAsString(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize fields for finding " + f.getId(), e);
        }
    }
}
