package com.martecyber.ares.assets;

/** Reachability of a service from a given source IP, as observed by a port scanner. */
public enum ServiceVisibilityState {
    OPEN,
    FILTERED,
    CLOSED;

    public static ServiceVisibilityState parse(String raw) {
        if (raw == null) return OPEN;
        return switch (raw.trim().toLowerCase()) {
            case "open"                    -> OPEN;
            case "filtered", "open|filtered" -> FILTERED;
            case "closed"                  -> CLOSED;
            default                        -> OPEN;
        };
    }
}
