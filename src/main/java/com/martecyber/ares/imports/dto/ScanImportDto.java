package com.martecyber.ares.imports.dto;

import com.martecyber.ares.imports.ScanImport;
import java.time.OffsetDateTime;

public record ScanImportDto(
    Long id,
    Long projectId,
    String tool,
    String format,
    String status,
    String filename,
    int assetsCreated,
    int detectionsCreated,
    int detectionsUpdated,
    String sourceIp,
    String nacProfile,
    int visibilityRecorded,
    String errorMessage,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    boolean hasRollbackableChanges
) {
    public static ScanImportDto from(ScanImport s, boolean hasRollbackableChanges) {
        return new ScanImportDto(s.getId(), s.getProjectId(), s.getTool(), s.getFormat(),
            s.getStatus(), s.getFilename(), s.getAssetsCreated(), s.getDetectionsCreated(),
            s.getDetectionsUpdated(), s.getSourceIp(), s.getNacProfile(),
            s.getVisibilityRecorded(), s.getErrorMessage(),
            s.getCreatedAt(), s.getCompletedAt(), hasRollbackableChanges);
    }
}
