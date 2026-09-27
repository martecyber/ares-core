package com.martecyber.ares.integrations.notifications.markdown;

/** Slack's {@code mrkdwn} — see https://api.slack.com/reference/surfaces/formatting. No native
 *  heading syntax (falls back to bold); the only required escaping is the 3 HTML-ish chars
 *  Slack's API documents (mrkdwn has no general backslash-escape mechanism). */
final class SlackDialect implements Dialect {

    @Override public String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public String bold(String inner) { return "*" + inner + "*"; }
    @Override public String italic(String inner) { return "_" + inner + "_"; }
    @Override public String code(String inner) { return "`" + inner + "`"; }
    @Override public String codeBlock(String inner) { return "```\n" + inner + "\n```"; }
    @Override public String link(String text, String url) { return "<" + url + "|" + text + ">"; }
    @Override public String heading(int level, String inner) { return bold(inner); }
    @Override public String bulletItem(String inner) { return "• " + inner; }
    @Override public String orderedItem(int index, String inner) { return index + ". " + inner; }
    @Override public String blockquoteLine(String inner) { return "> " + inner; }
}
