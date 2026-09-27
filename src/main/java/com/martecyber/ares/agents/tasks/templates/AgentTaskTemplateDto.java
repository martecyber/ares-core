package com.martecyber.ares.agents.tasks.templates;

import java.time.OffsetDateTime;

public record AgentTaskTemplateDto(
    Long id,
    String name,
    String description,
    String tool,
    String format,
    /** JSONB string — same shape as agent_task.args. */
    String args,
    String nacProfile,
    Integer timeoutMinutes,
    Long creatorId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static AgentTaskTemplateDto from(AgentTaskTemplate t) {
        return new AgentTaskTemplateDto(
            t.getId(), t.getName(), t.getDescription(), t.getTool(), t.getFormat(),
            t.getArgs(), t.getNacProfile(), t.getTimeoutMinutes(), t.getCreatorId(),
            t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
