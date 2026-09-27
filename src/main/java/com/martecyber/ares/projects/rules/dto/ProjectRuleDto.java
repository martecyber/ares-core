package com.martecyber.ares.projects.rules.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.projects.rules.ProjectRule;

import java.time.OffsetDateTime;
import java.util.Map;

public record ProjectRuleDto(
    Long id,
    Long projectId,
    String ruleType,
    boolean enabled,
    String note,
    Map<String, Object> config,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProjectRuleDto from(ProjectRule r) {
        Map<String, Object> cfg;
        try {
            cfg = MAPPER.readValue(r.getConfig(), new TypeReference<>() {});
        } catch (Exception e) {
            cfg = Map.of();
        }
        return new ProjectRuleDto(
            r.getId(), r.getProjectId(), r.getRuleType(), r.isEnabled(),
            r.getNote(), cfg, r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
