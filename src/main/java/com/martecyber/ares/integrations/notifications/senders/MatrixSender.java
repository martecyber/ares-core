package com.martecyber.ares.integrations.notifications.senders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Matrix.org Client-Server API sender.
 *
 * Config shape:
 * <pre>
 * {
 *   "homeserverUrl": "https://matrix.org",   // base URL of the homeserver (no trailing slash)
 *   "accessToken":   "syt_...",              // access token of the bot/user account
 *   "roomId":        "!roomid:matrix.org"   // room the bot has been invited to
 * }
 * </pre>
 *
 * Sends a {@code m.notice} message (rendered as HTML) via
 * {@code PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}}.
 */
@Component
public class MatrixSender implements MessagingSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Override public MessagingKind kind() { return MessagingKind.MATRIX; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        String homeserver   = DiscordSender.requireStr(config, "homeserverUrl").stripTrailing().replaceAll("/$", "");
        String accessToken  = DiscordSender.requireStr(config, "accessToken");
        String roomId       = DiscordSender.requireStr(config, "roomId");

        String encoded = URLEncoder.encode(roomId, StandardCharsets.UTF_8);
        String txnId   = UUID.randomUUID().toString().replace("-", "");
        String url     = homeserver + "/_matrix/client/v3/rooms/" + encoded + "/send/m.room.message/" + txnId;

        String colorHex = String.format("#%06X", msg.colorRgb());
        // Plain body always uses the raw source (markdown or not) as a client-agnostic fallback —
        // a common pattern for Matrix bots when a formatted_body is also present.
        String bodyContent = msg.markdown() ? NotificationMarkdownRenderer.toHtml(msg.body()) : escHtml(msg.body());
        String htmlBody = "<strong>" + escHtml(msg.title()) + "</strong>"
            + "<br/><font color=\"" + colorHex + "\">● </font>" + bodyContent
            + (msg.url() != null ? "<br/><a href=\"" + msg.url() + "\">View in Ares</a>" : "");
        String plainBody = msg.title() + "\n" + msg.body()
            + (msg.url() != null ? "\n" + msg.url() : "");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype",        "m.notice");
        payload.put("body",           plainBody);
        payload.put("format",         "org.matrix.custom.html");
        payload.put("formatted_body", htmlBody);

        // uri(URI): avoid RestClient's default template-encoding double-escaping the already
        // URLEncoder-encoded room ID segment above.
        http.put().uri(URI.create(url))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .body(MAPPER.writeValueAsString(payload))
            .retrieve().toBodilessEntity();
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
