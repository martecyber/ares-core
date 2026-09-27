package com.martecyber.ares.integrations.notifications.markdown;

/**
 * One messaging kind's own rich-text syntax — see {@link NotificationMarkdownRenderer}, which
 * walks a parsed CommonMark tree and asks a {@code Dialect} how to render each node. Scoped to
 * exactly what ares-ui's {@code MarkdownEditor.vue} toolbar can author (bold, italic, inline
 * code, heading, bullet/ordered list, link, fenced code block) plus blockquotes, since a user can
 * still type raw source the toolbar doesn't produce.
 */
interface Dialect {

    /** Escapes a literal text run so none of its characters are misread as this dialect's own
     *  markup — e.g. an asset identifier like {@code my_host} showing up as literal text, not
     *  accidentally turning italic. */
    String escapeText(String literal);

    String bold(String inner);

    String italic(String inner);

    String code(String inner);

    String codeBlock(String inner);

    String link(String text, String url);

    /** Most target dialects have no heading syntax of their own — falls back to {@link #bold}. */
    String heading(int level, String inner);

    String bulletItem(String inner);

    String orderedItem(int index, String inner);

    String blockquoteLine(String inner);

    /** Separator between block-level elements (paragraphs, headings, lists, code blocks, ...). */
    default String blockSeparator() {
        return "\n\n";
    }
}
