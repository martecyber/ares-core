package com.martecyber.ares.kb.wordlists;

import java.time.OffsetDateTime;

public record KbWordlistDto(
    Long id,
    Long folderId,
    String name,
    String description,
    String objectKey,
    Long sizeBytes,
    String sha256,
    Long lineCount,
    Long sourceRepoId,
    String sourceRepoPath,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static KbWordlistDto from(KbWordlist e) {
        return new KbWordlistDto(
            e.getId(),
            e.getFolderId(),
            e.getName(),
            e.getDescription(),
            e.getObjectKey(),
            e.getSizeBytes(),
            e.getSha256(),
            e.getLineCount(),
            e.getSourceRepoId(),
            e.getSourceRepoPath(),
            e.getCreatedAt(),
            e.getUpdatedAt()
        );
    }
}
