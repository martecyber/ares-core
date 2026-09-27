package com.martecyber.ares.integrations.notifications.markdown;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders a CommonMark-authored {@code NotificationMessage.body} (ares-ui's {@code
 * MarkdownEditor.vue} is the one input dialect — see the Workflows ACTION_NOTIFICATION node) into
 * each messaging kind's own native rich-text syntax. A raw pass-through would show literal
 * asterisks/hashes on Slack/Telegram/Teams instead of actual formatting — each target has its own
 * incompatible dialect (or, for Telegram, actively rejects/garbles unescaped CommonMark).
 */
public final class NotificationMarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder().build();

    private NotificationMarkdownRenderer() {}

    public static String toSlack(String markdown) { return render(markdown, new SlackDialect()); }

    public static String toTelegram(String markdown) { return render(markdown, new TelegramDialect()); }

    public static String toTeams(String markdown) { return render(markdown, new TeamsDialect()); }

    public static String toDiscord(String markdown) { return render(markdown, new DiscordDialect()); }

    /** Escapes and formats a short plain-text field (title, url) using Telegram's MarkdownV2
     *  reserved-character rules — for when the surrounding message is sent with {@code
     *  parse_mode: "MarkdownV2"} but this particular field isn't itself markdown-authored. */
    public static String toTelegramEscapedPlainText(String plainText) {
        return plainText == null ? "" : new TelegramDialect().escapeText(plainText);
    }

    /** Real HTML — for Matrix's {@code formatted_body} and the generic webhook's optional {@code
     *  bodyHtml} field. Reuses the same commonmark {@code HtmlRenderer} already used for Finding
     *  fields/reports, so this matches what "rendered markdown" means everywhere else in the app. */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        return HTML_RENDERER.render(PARSER.parse(markdown));
    }

    private static String render(String markdown, Dialect dialect) {
        if (markdown == null || markdown.isBlank()) return "";
        Node document = PARSER.parse(markdown);
        return new DialectRenderer(dialect).render(document);
    }
}
