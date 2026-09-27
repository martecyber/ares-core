package com.martecyber.ares.integrations.notifications.markdown;

/** Microsoft Teams Adaptive Card {@code TextBlock} markdown subset — bold/italic/links are
 *  native CommonMark syntax already; headings aren't supported at all (fall back to bold) and
 *  there's no real fenced-code-block rendering (best-effort: each line wrapped in its own code
 *  span). No documented backslash-escape mechanism, so literal text passes through unmodified —
 *  matches this integration's pre-existing (pre-markdown-support) behavior. */
final class TeamsDialect implements Dialect {

    @Override public String escapeText(String s) { return s; }

    @Override public String bold(String inner) { return "**" + inner + "**"; }
    @Override public String italic(String inner) { return "_" + inner + "_"; }
    @Override public String code(String inner) { return "`" + inner + "`"; }
    @Override public String codeBlock(String inner) { return "`" + inner.replace("\n", "`  \n`") + "`"; }
    @Override public String link(String text, String url) { return "[" + text + "](" + url + ")"; }
    @Override public String heading(int level, String inner) { return bold(inner); }
    @Override public String bulletItem(String inner) { return "- " + inner; }
    @Override public String orderedItem(int index, String inner) { return index + ". " + inner; }
    @Override public String blockquoteLine(String inner) { return "> " + inner; }
}
