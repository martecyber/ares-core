package com.martecyber.ares.common;

import java.util.Map;

/**
 * Ares's internal P0-P4 priority scale — the default display label for each severity
 * level, wherever one isn't explicitly configured (e.g. a report template's per-level
 * label). Mirrors {@code severityToPriorityLabel()} in {@code ares-ui/src/utils/cvss.ts}
 * — keep both in sync if either changes.
 */
public final class PriorityLabels {

    private PriorityLabels() {}

    private static final Map<String, String> LABELS = Map.of(
        "critical", "P0",
        "high",     "P1",
        "medium",   "P2",
        "low",      "P3",
        "info",     "P4"
    );

    /** Unrecognized or null input -> "P?" (unknown-priority placeholder). */
    public static String forSeverity(String severity) {
        if (severity == null) return "P?";
        return LABELS.getOrDefault(severity.toLowerCase(), "P?");
    }
}
