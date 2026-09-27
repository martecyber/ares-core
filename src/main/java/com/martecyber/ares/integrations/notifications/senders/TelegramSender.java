package com.martecyber.ares.integrations.notifications.senders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

/**
 * Telegram Bot API. Config shape: {@code {"botToken": "...", "chatId": "..."}}.
 * The bot must have been added to the chat (or the user must have /start'ed it in DMs).
 * We send Markdown-formatted text and rely on Telegram's lightweight client-side rendering.
 */
@Component
public class TelegramSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.TELEGRAM; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String token  = DiscordSender.requireStr(config, "botToken");
        String chatId = DiscordSender.requireStr(config, "chatId");

        String text;
        String parseMode;
        if (msg.markdown()) {
            // MarkdownV2 — needed for reliable nested formatting (bold title + rendered body in
            // one text field means both must use the same parse_mode/escaping rules).
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(NotificationMarkdownRenderer.toTelegramEscapedPlainText(msg.title())).append("*\n\n")
                .append(NotificationMarkdownRenderer.toTelegram(msg.body()));
            if (msg.url() != null) sb.append("\n\n").append(NotificationMarkdownRenderer.toTelegramEscapedPlainText(msg.url()));
            text = sb.toString();
            parseMode = "MarkdownV2";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(escapeMd(msg.title())).append("*\n\n").append(escapeMd(msg.body()));
            if (msg.url() != null) sb.append("\n\n").append(msg.url());
            text = sb.toString();
            parseMode = "Markdown";
        }

        Map<String, Object> payload = Map.of(
            "chat_id", chatId,
            "text",    text,
            "parse_mode", parseMode,
            "disable_web_page_preview", true
        );
        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        // uri(URI): avoid RestClient's default template-encoding re-escaping an already-encoded URL.
        http.post().uri(URI.create(url))
            .contentType(MediaType.APPLICATION_JSON)
            .body(MAPPER.writeValueAsString(payload))
            .retrieve().toBodilessEntity();
    }

    /** Conservative Markdown v1 escape so user-supplied titles/bodies don't break formatting. */
    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
