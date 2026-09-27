package com.martecyber.ares.auth.dto;

import com.martecyber.ares.auth.UserMfaDevice;
import java.time.OffsetDateTime;

public record MfaDeviceDto(Long id, Long userId, String type, String label, OffsetDateTime lastUsed) {
    public static MfaDeviceDto from(UserMfaDevice d) {
        return new MfaDeviceDto(d.getId(), d.getUserId(), d.getType(), d.getLabel(), d.getLastUsed());
    }
}
