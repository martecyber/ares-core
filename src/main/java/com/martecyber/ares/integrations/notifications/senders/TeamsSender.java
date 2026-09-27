package com.martecyber.ares.integrations.notifications.senders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Teams incoming webhook, via a Workflows (Power Automate) "Post to a channel/chat
 * when a webhook request is received" flow — e.g. the "Send webhook alerts to chat" template.
 * Config shape: {@code {"webhookUrl": "https://.../triggers/manual/paths/invoke?..."}}.
 * The flow's default trigger schema expects a message envelope with an Adaptive Card
 * attachment (not the legacy MessageCard format), so we send that shape here.
 */
@Component
public class TeamsSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.TEAMS; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String url = DiscordSender.requireStr(config, "webhookUrl");

        List<Object> body = new java.util.ArrayList<>();
        Map<String, Object> titleBlock = new java.util.LinkedHashMap<>();
        titleBlock.put("type", "TextBlock");
        titleBlock.put("text", msg.title());
        titleBlock.put("weight", "Bolder");
        titleBlock.put("size", "Medium");
        titleBlock.put("wrap", true);
        String accent = accentColor(msg.severity());
        if (accent != null) titleBlock.put("color", accent);
        body.add(titleBlock);
        String bodyText = msg.markdown() ? NotificationMarkdownRenderer.toTeams(msg.body()) : msg.body();
        body.add(Map.of("type", "TextBlock", "text", bodyText, "wrap", true));

        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("version", "1.4");
        card.put("body", body);
        if (msg.url() != null) {
            card.put("actions", List.of(Map.of(
                "type", "Action.OpenUrl",
                "title", "Open in Ares",
                "url", msg.url()
            )));
        }

        Map<String, Object> envelope = Map.of(
            "type", "message",
            "attachments", List.of(Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", card
            ))
        );

        // uri(URI) instead of uri(String): the Power Automate SAS query string is already
        // percent-encoded, and RestClient's default template resolver would re-encode it
        // (%2F -> %252F), breaking Azure's signature check.
        http.post().uri(URI.create(url))
            .contentType(MediaType.APPLICATION_JSON)
            .body(MAPPER.writeValueAsString(envelope))
            .retrieve().toBodilessEntity();
    }

    /** Adaptive Card TextBlock colors are a fixed enum (no arbitrary hex like MessageCard's themeColor). */
    private static String accentColor(String severity) {
        if (severity == null) return null;
        return switch (severity.toLowerCase()) {
            case "critical", "high" -> "Attention";
            case "medium"           -> "Warning";
            case "low"              -> "Good";
            case "info"             -> "Accent";
            default                 -> null;
        };
    }
}
