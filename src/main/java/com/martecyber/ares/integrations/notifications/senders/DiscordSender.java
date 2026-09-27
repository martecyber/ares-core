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
 * Discord incoming webhook. Config shape: {@code {"webhookUrl": "https://discord.com/api/webhooks/..."}}.
 * We post an embed so severity color renders as the embed strip on the left.
 */
@Component
public class DiscordSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.DISCORD; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String url = requireStr(config, "webhookUrl");
        Map<String, Object> embed = new java.util.LinkedHashMap<>();
        embed.put("title", msg.title());
        embed.put("description", msg.markdown() ? NotificationMarkdownRenderer.toDiscord(msg.body()) : msg.body());
        embed.put("color", msg.colorRgb());
        if (msg.url() != null) embed.put("url", msg.url());

        Map<String, Object> payload = Map.of("embeds", List.of(embed));
        // uri(URI): avoid RestClient's default template-encoding re-escaping an already-encoded URL.
        http.post().uri(URI.create(url))
            .contentType(MediaType.APPLICATION_JSON)
            .body(MAPPER.writeValueAsString(payload))
            .retrieve().toBodilessEntity();
    }

    static String requireStr(Map<String, Object> config, String key) {
        Object v = config.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("Missing '" + key + "' in integration config");
        return v.toString();
    }
}
