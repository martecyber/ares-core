package com.martecyber.ares.kb.testing.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.kb.testing.TestingProcedure;

import java.time.OffsetDateTime;
import java.util.List;

public record TestingProcedureDto(
    Long id,
    String title,
    /** Rich HTML. Null in list responses to keep them light; populated on detail. */
    String content,
    List<String> tags,
    Long creatorId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<TestingProcedureGuidePointRefDto> guidePoints,
    List<TestingProcedureExternalRefDto> externalRefs
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};

    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return MAPPER.readValue(json, STR_LIST); }
        catch (Exception e) { return List.of(); }
    }

    /** List row: no content body, no links resolved. */
    public static TestingProcedureDto summary(TestingProcedure p) {
        return new TestingProcedureDto(
            p.getId(), p.getTitle(), null, parseTags(p.getTags()),
            p.getCreatorId(), p.getCreatedAt(), p.getUpdatedAt(), null, null
        );
    }

    public static TestingProcedureDto detail(TestingProcedure p,
                                             List<TestingProcedureGuidePointRefDto> guidePoints,
                                             List<TestingProcedureExternalRefDto> externalRefs) {
        return new TestingProcedureDto(
            p.getId(), p.getTitle(), p.getContent(), parseTags(p.getTags()),
            p.getCreatorId(), p.getCreatedAt(), p.getUpdatedAt(), guidePoints, externalRefs
        );
    }
}
