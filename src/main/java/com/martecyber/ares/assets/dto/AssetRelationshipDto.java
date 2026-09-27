package com.martecyber.ares.assets.dto;

import com.martecyber.ares.assets.AssetRelationship;
import java.time.OffsetDateTime;

public record AssetRelationshipDto(Long fromAssetId, Long toAssetId, String type,
                                   boolean directional, OffsetDateTime createdAt) {
    public static AssetRelationshipDto from(AssetRelationship r) {
        return new AssetRelationshipDto(r.getFromAssetId(), r.getToAssetId(), r.getType(),
            r.isDirectional(), r.getCreatedAt());
    }
}
