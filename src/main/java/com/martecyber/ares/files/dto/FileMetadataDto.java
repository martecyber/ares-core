package com.martecyber.ares.files.dto;

import com.martecyber.ares.files.FileMetadata;
import java.time.OffsetDateTime;

public record FileMetadataDto(
    Long id,
    Long organizationId,
    Long projectId,
    Long findingId,
    String originalName,
    String contentType,
    Long sizeBytes,
    Long uploadedBy,
    OffsetDateTime createdAt
) {
    public static FileMetadataDto from(FileMetadata f) {
        return new FileMetadataDto(f.getId(), f.getOrganizationId(), f.getProjectId(),
            f.getFindingId(), f.getOriginalName(), f.getContentType(), f.getSizeBytes(),
            f.getUploadedBy(), f.getCreatedAt());
    }
}
