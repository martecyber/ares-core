package com.martecyber.ares.integrations.notifications.markdown;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

/**
 * Recursive-descent renderer from a parsed CommonMark tree to one {@link Dialect}'s native
 * syntax — plain instanceof-dispatch rather than commonmark-java's {@code AbstractVisitor}, since
 * several node kinds need their FULLY RENDERED children as a string before deciding how to wrap
 * them (e.g. a heading needs the rendered inner text to hand to {@link Dialect#heading}), not a
 * single shared mutable output buffer.
 */
final class DialectRenderer {

    private final Dialect dialect;

    DialectRenderer(Dialect dialect) {
        this.dialect = dialect;
    }

    String render(Node document) {
        return renderBlocks(document).strip();
    }

    private String renderBlocks(Node parent) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            String block = renderBlock(child);
            if (block == null) continue;
            if (!first) out.append(dialect.blockSeparator());
            out.append(block);
            first = false;
        }
        return out.toString();
    }

    private String renderBlock(Node node) {
        if (node instanceof Paragraph) return renderInline(node);
        if (node instanceof Heading h) return dialect.heading(h.getLevel(), renderInline(h));
        if (node instanceof FencedCodeBlock c) return dialect.codeBlock(c.getLiteral().stripTrailing());
        if (node instanceof IndentedCodeBlock c) return dialect.codeBlock(c.getLiteral().stripTrailing());
        if (node instanceof BlockQuote bq) return renderBlockQuote(bq);
        if (node instanceof BulletList list) return renderList(list, false);
        if (node instanceof OrderedList list) return renderList(list, true);
        if (node instanceof ThematicBreak) return "---";
        if (node instanceof HtmlBlock hb) return dialect.escapeText(hb.getLiteral().strip());
        // Unknown/unsupported block type — best-effort: render its children inline rather than dropping content.
        return renderInline(node);
    }

    private String renderBlockQuote(BlockQuote blockQuote) {
        String inner = renderBlocks(blockQuote);
        StringBuilder out = new StringBuilder();
        String[] lines = inner.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(dialect.blockquoteLine(lines[i]));
        }
        return out.toString();
    }

    private String renderList(Node list, boolean ordered) {
        int index = 1;
        if (ordered && list instanceof OrderedList ol && ol.getMarkerStartNumber() != null) {
            index = ol.getMarkerStartNumber();
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem li)) continue;
            String inner = renderBlocks(li).strip();
            if (!first) out.append('\n');
            first = false;
            out.append(ordered ? dialect.orderedItem(index++, inner) : dialect.bulletItem(inner));
        }
        return out.toString();
    }

    private String renderInline(Node parent) {
        StringBuilder out = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            out.append(renderInlineNode(child));
        }
        return out.toString();
    }

    private String renderInlineNode(Node node) {
        if (node instanceof Text t) return dialect.escapeText(t.getLiteral());
        if (node instanceof Emphasis) return dialect.italic(renderInline(node));
        if (node instanceof StrongEmphasis) return dialect.bold(renderInline(node));
        if (node instanceof Code c) return dialect.code(c.getLiteral());
        if (node instanceof Link link) return dialect.link(renderInline(link), link.getDestination());
        if (node instanceof Image img) return dialect.link(renderInline(img), img.getDestination());
        if (node instanceof SoftLineBreak) return " ";
        if (node instanceof HardLineBreak) return "\n";
        if (node instanceof HtmlInline hi) return dialect.escapeText(hi.getLiteral());
        // Nested block inside an inline context shouldn't normally happen — best-effort fallback.
        return renderInline(node);
    }
}
