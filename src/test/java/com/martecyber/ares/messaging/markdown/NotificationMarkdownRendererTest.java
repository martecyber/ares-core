package com.martecyber.ares.messaging.markdown;

import org.junit.jupiter.api.Test;

import com.martecyber.ares.integrations.notifications.markdown.NotificationMarkdownRenderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationMarkdownRendererTest {

    @Test
    void slackBoldItalicCodeAndLink() {
        assertEquals("*bold*", NotificationMarkdownRenderer.toSlack("**bold**"));
        assertEquals("_italic_", NotificationMarkdownRenderer.toSlack("*italic*"));
        assertEquals("`code`", NotificationMarkdownRenderer.toSlack("`code`"));
        assertEquals("<https://ares.example/x|link text>", NotificationMarkdownRenderer.toSlack("[link text](https://ares.example/x)"));
    }

    @Test
    void slackHeadingFallsBackToBold() {
        assertEquals("*Heading*", NotificationMarkdownRenderer.toSlack("# Heading"));
    }

    @Test
    void slackEscapesAmpersandAngleBrackets() {
        assertEquals("A &amp; B &lt;tag&gt;", NotificationMarkdownRenderer.toSlack("A & B <tag>"));
    }

    @Test
    void slackBulletAndOrderedLists() {
        assertEquals("• one\n• two", NotificationMarkdownRenderer.toSlack("- one\n- two"));
        assertEquals("1. one\n2. two", NotificationMarkdownRenderer.toSlack("1. one\n2. two"));
    }

    @Test
    void telegramSingleAsteriskBoldAndReservedCharsEscaped() {
        assertEquals("*bold*", NotificationMarkdownRenderer.toTelegram("**bold**"));
        // A literal '.' and '-' in plain text must be backslash-escaped for MarkdownV2.
        assertEquals("10\\.0\\.0\\.5 \\- prod", NotificationMarkdownRenderer.toTelegram("10.0.0.5 - prod"));
    }

    @Test
    void telegramHeadingFallsBackToBold() {
        assertEquals("*Heading*", NotificationMarkdownRenderer.toTelegram("# Heading"));
    }

    @Test
    void telegramLinkEscapesClosingParenInUrl() {
        // CommonMark link-destination syntax terminates a bare (unescaped-in-source) URL at the
        // first ')' — to get a literal ')' *inside* the parsed URL the source must escape it.
        assertEquals("[text](https://example.com/a\\)b)", NotificationMarkdownRenderer.toTelegram("[text](https://example.com/a\\)b)"));
    }

    @Test
    void telegramCodeSpanEscapesBacktickAndBackslash() {
        assertEquals("`a\\\\b`", NotificationMarkdownRenderer.toTelegram("`a\\b`"));
    }

    @Test
    void telegramEscapedPlainTextForTitleField() {
        assertEquals("Alert\\!", NotificationMarkdownRenderer.toTelegramEscapedPlainText("Alert!"));
    }

    @Test
    void teamsBoldItalicAndHeadingFallback() {
        assertEquals("**bold**", NotificationMarkdownRenderer.toTeams("**bold**"));
        assertEquals("_italic_", NotificationMarkdownRenderer.toTeams("*italic*"));
        assertEquals("**Heading**", NotificationMarkdownRenderer.toTeams("# Heading"));
    }

    @Test
    void discordSupportsNativeHeadingsAndEscapesLiteralUnderscore() {
        assertEquals("# Heading", NotificationMarkdownRenderer.toDiscord("# Heading"));
        assertEquals("my\\_host\\_name", NotificationMarkdownRenderer.toDiscord("my_host_name"));
    }

    @Test
    void discordBoldAndItalic() {
        assertEquals("**bold**", NotificationMarkdownRenderer.toDiscord("**bold**"));
        assertEquals("*italic*", NotificationMarkdownRenderer.toDiscord("*italic*"));
    }

    @Test
    void htmlRendersRealTags() {
        String html = NotificationMarkdownRenderer.toHtml("**bold** and [link](https://example.com)");
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<a href=\"https://example.com\">link</a>"));
    }

    @Test
    void blankOrNullInputRendersEmpty() {
        assertEquals("", NotificationMarkdownRenderer.toSlack(null));
        assertEquals("", NotificationMarkdownRenderer.toSlack(""));
        assertEquals("", NotificationMarkdownRenderer.toHtml(null));
    }

    @Test
    void multiParagraphBodySeparatedByBlankLine() {
        assertEquals("first\n\nsecond", NotificationMarkdownRenderer.toSlack("first\n\nsecond"));
    }

    @Test
    void codeBlockFenced() {
        assertEquals("```\nline1\nline2\n```", NotificationMarkdownRenderer.toSlack("```\nline1\nline2\n```"));
    }

    @Test
    void blockquoteLinePrefixed() {
        assertEquals("> quoted text", NotificationMarkdownRenderer.toSlack("> quoted text"));
    }
}
