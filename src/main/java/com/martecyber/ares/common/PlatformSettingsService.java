package com.martecyber.ares.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.ZoneId;

/**
 * Reads/writes platform-wide settings persisted in {@code ares.platform_setting}.
 * Each setting falls back to its application.yml value when the DB has no override.
 * The timezone is cached (read on every task dispatch); the public URL is not.
 */
@Service
public class PlatformSettingsService {

    private static final String KEY_TIMEZONE = "timezone";
    private static final String KEY_PUBLIC_URL = "public_url";

    @Value("${ares.timezone:UTC}")
    private String defaultTimezone;

    @Value("${ares.public-url:}")
    private String defaultPublicUrl;

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

    /**
     * Externally reachable base URL of Ares, without a trailing slash, or "" when unset.
     * The DB value wins over {@code ares.public-url}.
     */
    public String getPublicUrl() {
        String v = repo.findById(KEY_PUBLIC_URL).map(PlatformSetting::getValue).orElse(defaultPublicUrl);
        return v == null ? "" : v.strip().replaceAll("/+$", "");
    }

    /** Blank clears the DB value, falling back to {@code ares.public-url}. */
    public void setPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            repo.findById(KEY_PUBLIC_URL).ifPresent(repo::delete);
            return;
        }
        String v = url.strip().replaceAll("/+$", "");
        try {
            URI u = URI.create(v);
            if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    || u.getQuery() != null || u.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "publicUrl must be an absolute http(s) URL, e.g. https://ares.example.com");
        }
        PlatformSetting s = repo.findById(KEY_PUBLIC_URL).orElseGet(() -> {
            PlatformSetting n = new PlatformSetting();
            n.setKey(KEY_PUBLIC_URL);
            return n;
        });
        s.setValue(v);
        repo.save(s);
    }

    /**
     * Base URL for links and installers handed to external clients: the public URL when set,
     * otherwise derived from the request (honouring reverse-proxy X-Forwarded-* headers).
     */
    public String resolveBaseUrl(HttpServletRequest req) {
        String configured = getPublicUrl();
        if (!configured.isBlank()) return configured;
        String proto = firstNonBlank(req.getHeader("X-Forwarded-Proto"), req.getScheme());
        String host  = firstNonBlank(req.getHeader("X-Forwarded-Host"), req.getHeader("Host"));
        if (host == null) {
            int port = req.getServerPort();
            host = req.getServerName() + (port == 80 || port == 443 ? "" : ":" + port);
        }
        return proto + "://" + host;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
