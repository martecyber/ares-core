package com.martecyber.ares.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;

/**
 * Reads/writes platform-wide settings persisted in {@code ares.platform_setting}.
 * Falls back to the value from application.yml when the DB has no override.
 * A volatile cache avoids a DB hit on every task dispatch.
 */
@Service
public class PlatformSettingsService {

    private static final String KEY_TIMEZONE = "timezone";

    @Value("${ares.timezone:UTC}")
    private String defaultTimezone;

    private final PlatformSettingRepository repo;

    /** Cleared on every write so the next read re-fetches from the DB. */
    private volatile String cachedTimezone;

    public PlatformSettingsService(PlatformSettingRepository repo) {
        this.repo = repo;
    }

    public String getTimezone() {
        if (cachedTimezone != null) return cachedTimezone;
        cachedTimezone = repo.findById(KEY_TIMEZONE)
                .map(PlatformSetting::getValue)
                .orElse(defaultTimezone);
        return cachedTimezone;
    }

    public void setTimezone(String tz) {
        if (tz == null || tz.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone must not be blank");
        }
        try {
            ZoneId.of(tz); // validate
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid IANA timezone identifier: " + tz);
        }
        PlatformSetting s = repo.findById(KEY_TIMEZONE).orElseGet(() -> {
            PlatformSetting n = new PlatformSetting();
            n.setKey(KEY_TIMEZONE);
            return n;
        });
        s.setValue(tz);
        repo.save(s);
        cachedTimezone = null; // invalidate cache
    }
}
