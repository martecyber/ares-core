package com.martecyber.ares.kb.wordlists;

import java.time.OffsetDateTime;

public record KbWordlistFolderDto(
    Long id,
    Long parentId,
    String name,
    OffsetDateTime createdAt
) {
    public static KbWordlistFolderDto from(KbWordlistFolder e) {
        return new KbWordlistFolderDto(
            e.getId(),
            e.getParentId(),
            e.getName(),
            e.getCreatedAt()
        );
    }
}
