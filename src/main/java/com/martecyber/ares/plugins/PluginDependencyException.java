package com.martecyber.ares.plugins;

/**
 * Thrown by {@link PluginService#install} when a plugin's own {@link PluginManifest#dependsOn}
 * can't currently be satisfied — the dependency isn't installed at all, or is installed but
 * disabled. Carries structured {@code pluginId}/{@code dependencyId}/{@code disabled} fields (not
 * just a message string) so {@code GlobalExceptionHandler} can hand the frontend enough to show a
 * dedicated "can't install — missing dependency" modal instead of a generic error toast, the same
 * way {@link MissingPluginException} lets a missing-plugin 404 be told apart from an ordinary one.
 */
public class PluginDependencyException extends RuntimeException {

    private final String pluginId;
    private final String dependencyId;
    private final boolean disabled;

    public PluginDependencyException(String pluginId, String dependencyId, boolean disabled) {
        super("Plugin '" + pluginId + "' requires plugin '" + dependencyId + "', which is "
            + (disabled ? "installed but disabled — enable it first" : "not installed"));
        this.pluginId = pluginId;
        this.dependencyId = dependencyId;
        this.disabled = disabled;
    }

    private PluginDependencyException(String message, String pluginId, String dependencyId) {
        super(message);
        this.pluginId = pluginId;
        this.dependencyId = dependencyId;
        this.disabled = false;
    }

    /** The dependency IS installed+enabled, just not at a version {@code requiredMin}/{@code
     *  requiredMax} allows — a distinct case from the two-arg constructor's "not installed at
     *  all"/"disabled", but reuses the same wire shape ({@code dependencyDisabled: false}); the
     *  accurate reason is carried in the message itself, which the frontend already shows as the
     *  modal's primary line (see {@code GlobalExceptionHandler#pluginDependency}). */
    public static PluginDependencyException versionMismatch(String pluginId, String dependencyId,
                                                              String installedVersion, String requiredMin, String requiredMax) {
        String range = requiredMin != null && requiredMax != null ? requiredMin + "–" + requiredMax
            : requiredMin != null ? ">= " + requiredMin
            : "<= " + requiredMax;
        return new PluginDependencyException(
            "Plugin '" + pluginId + "' requires plugin '" + dependencyId + "' " + range
                + ", but version " + installedVersion + " is installed",
            pluginId, dependencyId);
    }

    public String getPluginId() { return pluginId; }
    public String getDependencyId() { return dependencyId; }
    public boolean isDisabled() { return disabled; }
}
