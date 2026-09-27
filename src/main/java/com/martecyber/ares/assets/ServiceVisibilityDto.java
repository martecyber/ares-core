package com.martecyber.ares.assets;

import java.time.OffsetDateTime;

public record ServiceVisibilityDto(
    String sourceIp,
    String nacProfile,
    ServiceVisibilityState state,
    OffsetDateTime firstSeen,
    OffsetDateTime lastSeen
) {
    public static ServiceVisibilityDto from(ServiceVisibility v) {
        return new ServiceVisibilityDto(v.getSourceIp(), v.getNacProfile(), v.getState(),
            v.getFirstSeen(), v.getLastSeen());
    }
}
