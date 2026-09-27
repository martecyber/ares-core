package com.martecyber.ares.integrations.notifications;

import java.util.List;

/**
 * Transport-neutral notification: each sender renders it into its own payload shape.
 *  • {@code title}     short headline ("New CRITICAL detection in Project X").
 *  • {@code body}      plain or lightly-formatted multi-line message body.
 *  • {@code severity}  drives the color accent on rich transports (Discord embed,
 *                      Teams MessageCard); the generic webhook just passes it through.
 *  • {@code url}       optional link to the platform resource — null when there isn't one.
 *  • {@code markdown}  when true, {@code body} is CommonMark (authored via ares-ui's
 *                      MarkdownEditor.vue — see the Workflows ACTION_NOTIFICATION node) and each
 *                      sender converts it to its own native rich-text syntax before sending
 *                      ({@link com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer}).
 *                      False when {@code body} is already final HTML (an EmailTemplate KB
 *                      selection — see EmailSender) or for legacy plain-text bodies.
 *  • {@code to/cc/bcc} recipient addresses — unlike every other transport, email's destination is
 *                      per-send rather than baked into the integration config (see the Workflows
 *                      ACTION_NOTIFICATION node's To/CC/BCC fields, email-kind integrations only).
 *                      Empty for every non-email sender, which ignore these fields entirely.
 */
public record NotificationMessage(String title, String body, String severity, String url, boolean markdown,
                                   List<String> to, List<String> cc, List<String> bcc) {

    public NotificationMessage(String title, String body, String severity, String url) {
        this(title, body, severity, url, false, List.of(), List.of(), List.of());
    }

    public NotificationMessage(String title, String body, String severity, String url, boolean markdown) {
        this(title, body, severity, url, markdown, List.of(), List.of(), List.of());
    }

    /** Color codes used by Discord embeds and Teams MessageCards. Default = neutral. */
    public int colorRgb() {
        if (severity == null) return 0x4141A2; // ares accent
        return switch (severity.toLowerCase()) {
            case "critical" -> 0xEF4444;
            case "high"     -> 0xF97316;
            case "medium"   -> 0xEAB308;
            case "low"      -> 0x22C55E;
            case "info"     -> 0x3B82F6;
            default         -> 0x6B7280;
        };
    }
}
