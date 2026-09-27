package com.martecyber.ares.plugins;

import java.util.Comparator;

/**
 * Shared wording for "this feature needs a plugin that isn't currently registered" — used
 * anywhere a plugin might be missing, so the message is identical regardless of HOW the caller
 * found out (a Workflow node reaching an unregistered {@code IntegrationActionHandler} type, or
 * an HTTP request hitting a path a disabled/uninstalled plugin would have owned). Distinguishes
 * three cases: no matching plugin installed at all, installed but disabled, and installed+enabled
 * but somehow not actually registered (a real load failure — points at the server logs).
 */
public final class PluginMessages {

    private PluginMessages() {}

    /** Resolves a message for a KNOWN, specific plugin id — exact match only, no prefix guessing.
     *  Used when the caller already knows exactly which plugin governs the missing feature (e.g.
     *  {@code PluginFallbackController} matching a request path against {@code Plugin.
     *  ownedPathPrefixes}, which always names the owning plugin id directly). */
    public static String missingPluginMessage(PluginRepository pluginRepo, String featureLabel, String pluginId) {
        return format(featureLabel, pluginId, pluginRepo.findByPluginId(pluginId).orElse(null));
    }

    /** Resolves a message for a type string that might BE a plugin id, or might belong to a
     *  plugin that exposes more than one type under one id — exact match first, then longest-
     *  matching prefix (e.g. "tenable-mssp" resolving to the "tenable" plugin). Used when the
     *  caller only has a type/feature string to go on, not a pre-known plugin id. */
    public static String missingPluginMessageByPrefix(PluginRepository pluginRepo, String featureLabel, String typeOrPrefixCandidate) {
        Plugin match = pluginRepo.findByPluginId(typeOrPrefixCandidate).orElse(null);
        if (match == null) {
            match = pluginRepo.findAll().stream()
                .filter(p -> typeOrPrefixCandidate.toLowerCase().startsWith(p.getPluginId().toLowerCase()))
                .max(Comparator.comparingInt(p -> p.getPluginId().length()))
                .orElse(null);
        }
        return format(featureLabel, typeOrPrefixCandidate, match);
    }

    private static String format(String featureLabel, String key, Plugin match) {
        if (match == null) {
            return featureLabel + " '" + key + "' has no registered handler — no matching plugin is "
                + "installed. If this is a plugin-provided feature, install it from Administration → Plugins.";
        }
        if (!match.isEnabled()) {
            return featureLabel + " '" + key + "' requires the '" + match.getDisplayName() + "' plugin ("
                + match.getPluginId() + "), which is installed but disabled. Enable it from Administration → Plugins.";
        }
        return featureLabel + " '" + key + "' should be provided by the installed, enabled '" + match.getDisplayName()
            + "' plugin (" + match.getPluginId() + "), but it isn't currently registered — check the server logs "
            + "for a plugin load error.";
    }
}
