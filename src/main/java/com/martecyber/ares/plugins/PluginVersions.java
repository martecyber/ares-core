package com.martecyber.ares.plugins;

/**
 * Best-effort semver-ish comparison for {@code plugin.json}'s {@code version} field — used by
 * {@link PluginService} to tell an admin whether re-installing a plugin whose id is already
 * present is a same-version reinstall, an upgrade, or a downgrade, instead of just refusing it.
 *
 * <p>Splits on the first {@code "-"} to separate the numeric core from a prerelease/build suffix
 * (this repo's own {@code MAJOR.MINOR.PATCH-betaN} convention among others), compares the numeric
 * core dot-segment by dot-segment (a missing or non-numeric segment counts as {@code 0}), and —
 * only when the numeric cores are equal — falls back to the standard semver rule that a version
 * with no suffix outranks one with a suffix (a release beats any of its own prereleases), then
 * plain lexicographic suffix comparison as a last resort (enough to order {@code -beta1} before
 * {@code -beta2}; not a full implementation of semver's dotted-prerelease-identifier comparison
 * rules for every exotic suffix scheme).
 */
final class PluginVersions {

    private PluginVersions() {}

    /** Same contract as {@link Comparable#compareTo}: negative if {@code a < b}, zero if equal,
     *  positive if {@code a > b}. */
    static int compare(String a, String b) {
        String[] partsA = a.split("-", 2);
        String[] partsB = b.split("-", 2);

        int coreCompare = compareCore(partsA[0], partsB[0]);
        if (coreCompare != 0) return coreCompare;

        String suffixA = partsA.length > 1 ? partsA[1] : null;
        String suffixB = partsB.length > 1 ? partsB[1] : null;
        if (suffixA == null && suffixB == null) return 0;
        if (suffixA == null) return 1;
        if (suffixB == null) return -1;
        return suffixA.compareTo(suffixB);
    }

    private static int compareCore(String a, String b) {
        String[] segA = a.split("\\.");
        String[] segB = b.split("\\.");
        int len = Math.max(segA.length, segB.length);
        for (int i = 0; i < len; i++) {
            int na = i < segA.length ? parseIntSafe(segA[i]) : 0;
            int nb = i < segB.length ? parseIntSafe(segB[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
