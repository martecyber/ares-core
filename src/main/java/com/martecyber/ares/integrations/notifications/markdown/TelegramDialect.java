package com.martecyber.ares.integrations.notifications.markdown;

/** Telegram Bot API MarkdownV2 — see https://core.telegram.org/bots/api#markdownv2-style. Unlike
 *  the other dialects, literal text runs must have every reserved character backslash-escaped or
 *  the API rejects the whole message with a 400 (Telegram parses strictly, not leniently). Code
 *  spans/blocks have their own narrower escaping rule (only backtick and backslash). */
final class TelegramDialect implements Dialect {

    private static final String RESERVED = "_*[]()~`>#+-=|{}.!\\";

    @Override public String escapeText(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (RESERVED.indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static String escapeCode(String s) {
        return s.replace("\\", "\\\\").replace("`", "\\`");
    }

    @Override public String bold(String inner) { return "*" + inner + "*"; }
    @Override public String italic(String inner) { return "_" + inner + "_"; }
    @Override public String code(String inner) { return "`" + escapeCode(inner) + "`"; }
    @Override public String codeBlock(String inner) { return "```\n" + escapeCode(inner) + "\n```"; }

    @Override public String link(String text, String url) {
        String safeUrl = url == null ? "" : url.replace("\\", "\\\\").replace(")", "\\)");
        return "[" + text + "](" + safeUrl + ")";
    }

    @Override public String heading(int level, String inner) { return bold(inner); }
    @Override public String bulletItem(String inner) { return "• " + inner; }
    @Override public String orderedItem(int index, String inner) { return index + "\\. " + inner; }
    @Override public String blockquoteLine(String inner) { return ">" + inner; }
}
