package com.martecyber.ares.auth.dto;

import com.martecyber.ares.auth.UserApiToken;
import java.time.OffsetDateTime;

public record ApiTokenDto(Long id, Long userId, String name, String scopes,
                          OffsetDateTime expiresAt, OffsetDateTime lastUsedAt, OffsetDateTime createdAt) {
    public static ApiTokenDto from(UserApiToken t) {
        return new ApiTokenDto(t.getId(), t.getUserId(), t.getName(), t.getScopes(),
            t.getExpiresAt(), t.getLastUsedAt(), t.getCreatedAt());
    }
}
