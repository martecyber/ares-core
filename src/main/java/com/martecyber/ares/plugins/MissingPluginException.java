package com.martecyber.ares.plugins;

/**
 * Thrown wherever core code reaches a feature that a plugin is supposed to provide but currently
 * doesn't — the plugin isn't installed, is installed but disabled, or failed to register. Carries
 * the specific {@code pluginId} so {@code GlobalExceptionHandler} can surface it as a structured
 * 404 the frontend can turn into "this feature requires the X plugin" instead of a dead end.
 *
 * <p>See also {@code GlobalExceptionHandler#noHandlerFound} — a request to a path a currently-
 * unloaded plugin WOULD have registered a controller for never reaches any Java code to throw
 * this from directly (Spring MVC simply has no mapping for it); that case is instead detected via
 * {@link Plugin#getOwnedPathPrefixes()} once Spring gives up looking for a handler.
 */
public class MissingPluginException extends RuntimeException {

    private final String pluginId;

    public MissingPluginException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public String getPluginId() { return pluginId; }
}
