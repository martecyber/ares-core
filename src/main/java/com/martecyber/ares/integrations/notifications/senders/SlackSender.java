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
 * Slack incoming webhook. Config shape: {@code {"webhookUrl": "https://hooks.slack.com/services/..."}}.
 * Uses an attachment with a colored bar so severity is visible at a glance.
 */
@Component
public class SlackSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.SLACK; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String url = DiscordSender.requireStr(config, "webhookUrl");
        // #RRGGBB hex for the attachment color strip.
        String color = String.format("#%06X", msg.colorRgb());

        Map<String, Object> attachment = new java.util.LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("title", msg.title());
        attachment.put("text", msg.markdown() ? NotificationMarkdownRenderer.toSlack(msg.body()) : msg.body());
        if (msg.url() != null) attachment.put("title_link", msg.url());

        Map<String, Object> payload = Map.of(
            "text", msg.title(),
            "attachments", List.of(attachment)
        );
        // uri(URI): avoid RestClient's default template-encoding re-escaping an already-encoded URL.
        http.post().uri(URI.create(url))
            .contentType(MediaType.APPLICATION_JSON)
            .body(MAPPER.writeValueAsString(payload))
            .retrieve().toBodilessEntity();
    }
}
