package com.martecyber.ares.integrations.notifications.senders;

import com.martecyber.ares.integrations.notifications.MessagingKind;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.jsoup.Jsoup;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * SMTP email. Config shape:
 * {@code {"host": "...", "port": 587, "username": "...", "password": "...",
 *          "fromAddress": "ares@example.com", "useTls": true}} — {@code username}/{@code password}
 * are optional (unauthenticated relay, e.g. Mailpit in dev).
 *
 * Unlike every other transport, the destination isn't part of the integration config — it comes
 * from {@link NotificationMessage#to()}/{@code cc()}/{@code bcc()}, set per-send by the Workflows
 * ACTION_NOTIFICATION node's To/CC/BCC fields (see WorkflowRunService#sendNotificationCore). A
 * fresh {@link JavaMailSenderImpl} is built per send rather than a shared singleton bean, since
 * each integration can point at a different SMTP server, just as each Slack/Discord integration
 * has its own webhook URL.
 */
@Component
public class EmailSender implements MessagingSender {

    @Override public MessagingKind kind() { return MessagingKind.EMAIL; }

    @Override
    public void send(Map<String, Object> config, NotificationMessage msg) throws Exception {
        if (msg.to().isEmpty()) throw new IllegalArgumentException("No recipient (To) address configured");

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(DiscordSender.requireStr(config, "host"));
        mailSender.setPort(portOf(config));
        String username = strOrNull(config.get("username"));
        String password = strOrNull(config.get("password"));
        boolean auth = username != null && !username.isBlank();
        if (auth) {
            mailSender.setUsername(username);
            mailSender.setPassword(password);
        }
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(Boolean.TRUE.equals(config.get("useTls"))));

        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(DiscordSender.requireStr(config, "fromAddress"));
        helper.setTo(addresses(msg.to()));
        if (!msg.cc().isEmpty()) helper.setCc(addresses(msg.cc()));
        if (!msg.bcc().isEmpty()) helper.setBcc(addresses(msg.bcc()));
        // Strip control characters: msg.title() can carry workflow-templated user data (a
        // finding/detection title) and this becomes a raw SMTP header — a stray CR/LF would let
        // that data inject extra headers.
        helper.setSubject(stripControlChars(msg.title()));

        String html = msg.markdown() ? NotificationMarkdownRenderer.toHtml(msg.body()) : msg.body();
        String text = msg.markdown() ? msg.body() : Jsoup.parse(html).text();
        helper.setText(text, html);

        mailSender.send(mime);
    }

    private static int portOf(Map<String, Object> config) {
        Object v = config.get("port");
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) return Integer.parseInt(s.trim());
        throw new IllegalArgumentException("Missing 'port' in integration config");
    }

    private static String strOrNull(Object v) { return v == null ? null : v.toString(); }

    private static String stripControlChars(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ");
    }

    private static InternetAddress[] addresses(List<String> raw) throws Exception {
        InternetAddress[] out = new InternetAddress[raw.size()];
        for (int i = 0; i < raw.size(); i++) out[i] = new InternetAddress(raw.get(i), true);
        return out;
    }
}
