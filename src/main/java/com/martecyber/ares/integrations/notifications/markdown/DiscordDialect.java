package com.martecyber.ares.integrations.notifications.markdown;

/** Discord message/embed markdown — the closest of all target dialects to CommonMark itself
 *  (bold/italic/code/lists/blockquote/links, and headings since Discord added {@code #}/{@code
 *  ##}/{@code ###} support). Still escapes literal text runs so substituted values (e.g. an
 *  asset identifier containing {@code _}) can't be misread as Discord's own markup. */
final class DiscordDialect implements Dialect {

    private static final String RESERVED = "\\*_`~|>#";

    @Override public String escapeText(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (RESERVED.indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    @Override public String bold(String inner) { return "**" + inner + "**"; }
    @Override public String italic(String inner) { return "*" + inner + "*"; }
    @Override public String code(String inner) { return "`" + inner + "`"; }
    @Override public String codeBlock(String inner) { return "```\n" + inner + "\n```"; }
    @Override public String link(String text, String url) { return "[" + text + "](" + url + ")"; }
    @Override public String heading(int level, String inner) { return "#".repeat(Math.min(level, 3)) + " " + inner; }
    @Override public String bulletItem(String inner) { return "- " + inner; }
    @Override public String orderedItem(int index, String inner) { return index + ". " + inner; }
    @Override public String blockquoteLine(String inner) { return "> " + inner; }
}
