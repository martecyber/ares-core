package com.martecyber.ares.kb.wordlists;

import java.time.OffsetDateTime;

public record KbWordlistRepoDto(
    Long id,
    String repoUrl,
    String branch,
    String pathFilter,
    boolean importAllTypes,
    boolean hasToken,
    boolean autoSync,
    int syncIntervalHours,
    OffsetDateTime lastSyncAt,
    String lastSyncStatus,
    String lastSyncError,
    int fileCount,
    OffsetDateTime createdAt
) {
    public static KbWordlistRepoDto from(KbWordlistRepo r) {
        return new KbWordlistRepoDto(
            r.getId(), r.getRepoUrl(), r.getBranch(), r.getPathFilter(),
            r.isImportAllTypes(),
            r.getGithubToken() != null && !r.getGithubToken().isBlank(),
            r.isAutoSync(), r.getSyncIntervalHours(),
            r.getLastSyncAt(), r.getLastSyncStatus(), r.getLastSyncError(),
            r.getFileCount(), r.getCreatedAt()
        );
    }
}
