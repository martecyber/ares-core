package com.martecyber.ares.integrations.notifications.senders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic JSON webhook. Config shape:
 * {@code {"url": "https://...", "headers": {"X-Auth": "..."}}}.
 *
 * We POST a structured payload with the full {@link NotificationMessage} fields so the
 * receiver can route however it likes — no opinionated shape like Slack/Teams enforce.
 */
@Component
public class GenericWebhookSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.WEBHOOK; }

    @Override
    @SuppressWarnings("unchecked")
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String url = DiscordSender.requireStr(config, "url");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", msg.title());
        // body stays the raw (markdown or plain) source — receivers that don't care about rich
        // text ignore it exactly as before; bodyHtml is an added convenience, not a replacement.
        payload.put("body", msg.body());
        if (msg.markdown()) payload.put("bodyHtml", NotificationMarkdownRenderer.toHtml(msg.body()));
        payload.put("severity", msg.severity());
        if (msg.url() != null) payload.put("url", msg.url());

        // uri(URI): avoid RestClient's default template-encoding re-escaping an already-encoded URL.
        var req = http.post().uri(URI.create(url)).contentType(MediaType.APPLICATION_JSON);

        // Apply caller-supplied headers (auth tokens, signing prefixes, …). We trust the
        // admin-set keys; values pass through as Strings since we serialized them via JSON.
        Object hdrs = config.get("headers");
        if (hdrs instanceof Map<?, ?> hmap) {
            for (var e : ((Map<String, Object>) hmap).entrySet()) {
                if (e.getValue() != null) req.header(e.getKey(), e.getValue().toString());
            }
        }
        req.body(MAPPER.writeValueAsString(payload)).retrieve().toBodilessEntity();
    }
}
