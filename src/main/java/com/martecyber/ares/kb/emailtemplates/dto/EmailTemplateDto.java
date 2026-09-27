package com.martecyber.ares.kb.emailtemplates.dto;

import com.martecyber.ares.kb.emailtemplates.EmailTemplate;
import com.martecyber.ares.kb.emailtemplates.PriorityDisplayEntry;

import java.time.OffsetDateTime;
import java.util.Map;

public record EmailTemplateDto(
    Long id,
    String name,
    String subjectTemplate,
    /** Sanitized HTML. Null in list responses to keep them light; populated on detail. */
    String htmlContent,
    Map<String, PriorityDisplayEntry> priorityColors,
    Long creatorId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    /** List row: no HTML body. */
    public static EmailTemplateDto summary(EmailTemplate t) {
        return new EmailTemplateDto(
            t.getId(), t.getName(), t.getSubjectTemplate(), null, t.getPriorityColors(),
            t.getCreatorId(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }

    public static EmailTemplateDto detail(EmailTemplate t) {
        return new EmailTemplateDto(
            t.getId(), t.getName(), t.getSubjectTemplate(), t.getHtmlContent(), t.getPriorityColors(),
            t.getCreatorId(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
