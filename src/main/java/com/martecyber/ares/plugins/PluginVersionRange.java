package com.martecyber.ares.plugins;

/**
 * Inclusive min/max version-range membership, built on {@link PluginVersions#compare} — used to
 * check a plugin's declared {@code minAresApiVersion}/{@code maxAresApiVersion}/
 * {@code minAresUiVersion}/{@code maxAresUiVersion} (see {@link PluginManifest}) against this
 * running instance's own product version, and a {@link PluginManifest.PluginDependency}'s
 * {@code minVersion}/{@code maxVersion} against an already-installed dependency's version.
 *
 * <p>Deliberately not a semver-range expression parser ({@code ^1.2.3}, {@code >=1.0 <2.0},
 * ...) — see {@link PluginVersions}'s own doc comment for why "best-effort, this repo's own
 * convention" is enough for a single-vendor plugin ecosystem with a handful of plugins.
 */
final class PluginVersionRange {

    private PluginVersionRange() {}

    /** True when {@code version} is within [{@code min}, {@code max}] — either bound null means
     *  unbounded on that side; both null always returns true ("no constraint declared"). */
    static boolean isInRange(String version, String min, String max) {
        if (min != null && PluginVersions.compare(version, min) < 0) return false;
        if (max != null && PluginVersions.compare(version, max) > 0) return false;
        return true;
    }
}
