package com.martecyber.ares.workflows;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the deep link into the Ares UI for the entity a TRIGGER_EVENT fired about, exposed to
 * workflows as {@code {{trigger.url}}}. Paths mirror the UI router (ares-ui/src/router/index.ts);
 * keep them in sync when a detail route changes.
 *
 * <p>Returns {@code ""} when there is no meaningful page (deleted entities, or a type/scope
 * combination without enough ids). When no public URL is configured (Platform Configuration
 * setting or ARES_PUBLIC_URL) the link is relative ({@code /organization/...}): fine inside the
 * UI, but not clickable from Slack/email.
 */
final class WorkflowEntityLinks {

    private WorkflowEntityLinks() {}

    static String build(String baseUrl, String eventCode, Object entityId, Long orgId, Long projectId) {
        int dot = eventCode.indexOf('.');
        String type = eventCode.substring(0, dot);
        String action = eventCode.substring(dot + 1);
        if ("deleted".equals(action) || entityId == null) return "";

        String id = enc(String.valueOf(entityId));
        String path = switch (type) {
            case "detection", "finding", "asset" -> {
                String seg = type + "s";
                if (orgId == null) yield null;
                // Detections/findings always carry a project; assets are org-level.
                yield projectId != null && !"asset".equals(type)
                    ? "/organization/" + orgId + "/projects/" + projectId + "/" + seg + "/" + id
                    : "/organization/" + orgId + "/" + seg + "/" + id;
            }
            case "project" -> orgId == null ? null : "/organization/" + orgId + "/projects/" + id + "/dashboard";
            case "organization" -> "/organization/" + id;
            case "finding_template" -> "/kb/finding-templates/" + id;
            case "cve" -> "/kb/cve/" + id;
            default -> null;
        };
        if (path == null) return "";
        String base = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        return base + path;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
