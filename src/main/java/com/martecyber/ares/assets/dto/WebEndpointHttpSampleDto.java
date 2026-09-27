package com.martecyber.ares.assets.dto;

import com.martecyber.ares.assets.WebEndpointHttpSample;
import java.time.OffsetDateTime;

public record WebEndpointHttpSampleDto(
    Long id,
    Long assetId,
    String label,
    String requestContent,
    String responseContent,
    String notes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static WebEndpointHttpSampleDto from(WebEndpointHttpSample s) {
        return new WebEndpointHttpSampleDto(
            s.getId(), s.getAssetId(), s.getLabel(),
            s.getRequestContent(), s.getResponseContent(), s.getNotes(),
            s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
