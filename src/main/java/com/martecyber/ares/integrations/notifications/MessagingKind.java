package com.martecyber.ares.integrations.notifications;

import java.util.Locale;
import java.util.Set;

/**
 * Supported messaging integration kinds. Stored lowercase in {@code messaging_integration.kind}.
 * Adding a new transport: add the enum constant and a matching {@code MessagingSender} bean.
 */
public enum MessagingKind {
    DISCORD, SLACK, TELEGRAM, TEAMS, WEBHOOK, MATRIX, EMAIL;

    public static MessagingKind parse(String s) {
        if (s == null) throw new IllegalArgumentException("kind is required");
        try { return MessagingKind.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown messaging kind: " + s + " (allowed: " + Set.of(values()) + ")");
        }
    }

    public String code() { return name().toLowerCase(Locale.ROOT); }
}
