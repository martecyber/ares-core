package com.martecyber.ares.reporting;

import com.deepoove.poi.data.DocumentRenderData;
import com.deepoove.poi.data.NumberingRenderData;
import com.deepoove.poi.data.ParagraphRenderData;
import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.data.RenderData;
import com.deepoove.poi.data.TextRenderData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for {@link ReportGenerationService#renderFieldHtml}'s structured
 * ({@code _doc}) output — the Markdown → poi-tl DocumentRenderData walker added to make
 * generated Word content follow the template's own styles (Heading1/2/Quote, real list
 * numbering, inline bold/italic, images at their real position) instead of one flattened
 * plain-text paragraph. Manual end-to-end docx inspection is far too slow to iterate on
 * this recursive DOM-walking logic, hence unit coverage here.
 */
class ReportGenerationServiceMarkdownTest {

    @Test
    void emptyMarkdownProducesNoDocData() {
        var fc = ReportGenerationService.renderFieldHtml("");
        assertEquals("", fc.text());
        assertNull(fc.docData());

        var fc2 = ReportGenerationService.renderFieldHtml(null);
        assertNull(fc2.docData());
    }

    @Test
    void headingsGetWordBuiltInStyleIds() {
        var fc = ReportGenerationService.renderFieldHtml("# Title\n\n## Subtitle\n\nBody text.");
        DocumentRenderData doc = fc.docData();
        assertNotNull(doc);
        List<RenderData> contents = doc.getContents();
        assertEquals(3, contents.size());

        ParagraphRenderData h1 = (ParagraphRenderData) contents.get(0);
        assertEquals("Heading1", h1.getParagraphStyle().getStyleId());
        assertEquals("Title", firstText(h1));

        ParagraphRenderData h2 = (ParagraphRenderData) contents.get(1);
        assertEquals("Heading2", h2.getParagraphStyle().getStyleId());
        assertEquals("Subtitle", firstText(h2));

        ParagraphRenderData body = (ParagraphRenderData) contents.get(2);
        assertNull(body.getParagraphStyle()); // normal paragraph, no styleId override
        assertEquals("Body text.", firstText(body));
    }

    @Test
    void boldAndItalicBecomeSeparateStyledRuns() {
        var fc = ReportGenerationService.renderFieldHtml("Some **bold** and *italic* text.");
        ParagraphRenderData p = (ParagraphRenderData) fc.docData().getContents().get(0);
        List<RenderData> runs = p.getContents();

        TextRenderData bold = (TextRenderData) runs.stream()
            .filter(r -> r instanceof TextRenderData t && "bold".equals(t.getText())).findFirst().orElseThrow();
        assertTrue(bold.getStyle().isBold());

        TextRenderData italic = (TextRenderData) runs.stream()
            .filter(r -> r instanceof TextRenderData t && "italic".equals(t.getText())).findFirst().orElseThrow();
        assertTrue(italic.getStyle().isItalic());
    }

    @Test
    void bulletListBecomesRealNumbering() {
        var fc = ReportGenerationService.renderFieldHtml("- First item\n- Second **item**\n- Third item");
        List<RenderData> contents = fc.docData().getContents();
        assertEquals(1, contents.size());
        assertInstanceOf(NumberingRenderData.class, contents.get(0));
        NumberingRenderData numbering = (NumberingRenderData) contents.get(0);
        assertEquals(3, numbering.getItems().size());
    }

    @Test
    void numberedListUsesDecimalFormat() {
        var fc = ReportGenerationService.renderFieldHtml("1. Alpha\n2. Beta");
        NumberingRenderData numbering = (NumberingRenderData) fc.docData().getContents().get(0);
        assertEquals(2, numbering.getItems().size());
    }

    @Test
    void blockquoteGetsQuoteStyle() {
        var fc = ReportGenerationService.renderFieldHtml("> A quoted line.");
        ParagraphRenderData p = (ParagraphRenderData) fc.docData().getContents().get(0);
        assertEquals("Quote", p.getParagraphStyle().getStyleId());
        assertEquals("A quoted line.", firstText(p));
    }

    @Test
    void fencedCodeBlockIsOneParagraphPerLineWithMonospaceFont() {
        var fc = ReportGenerationService.renderFieldHtml("```\nline one\nline two\n```");
        List<RenderData> contents = fc.docData().getContents();
        assertEquals(2, contents.size());
        ParagraphRenderData l1 = (ParagraphRenderData) contents.get(0);
        assertEquals("line one", firstText(l1));
        assertEquals("Consolas", firstRun(l1).getStyle().getFontFamily());
        assertEquals("F2F2F2", l1.getParagraphStyle().getBackgroundColor());
    }

    @Test
    void standaloneImageBecomesItsOwnPictureParagraph() {
        // 1x1 transparent PNG, base64-encoded — decodeImage/naturalImageSize must handle it.
        String png1x1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        var fc = ReportGenerationService.renderFieldHtml("![alt](data:image/png;base64," + png1x1 + ")");
        List<RenderData> contents = fc.docData().getContents();
        assertEquals(1, contents.size());
        ParagraphRenderData p = (ParagraphRenderData) contents.get(0);
        assertInstanceOf(PictureRenderData.class, p.getContents().get(0));
    }

    @Test
    void imageInsideParagraphStaysInlineAtItsPosition() {
        String png1x1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        var fc = ReportGenerationService.renderFieldHtml(
            "Before ![alt](data:image/png;base64," + png1x1 + ") after.");
        ParagraphRenderData p = (ParagraphRenderData) fc.docData().getContents().get(0);
        List<RenderData> runs = p.getContents();
        // "Before " text, then the picture, then " after." text — image not shoved to the end.
        assertTrue(runs.get(0) instanceof TextRenderData before && before.getText().contains("Before"));
        assertInstanceOf(PictureRenderData.class, runs.get(1));
        assertTrue(runs.get(2) instanceof TextRenderData after && after.getText().contains("after"));
    }

    private static String firstText(ParagraphRenderData p) {
        return firstRun(p).getText();
    }

    private static TextRenderData firstRun(ParagraphRenderData p) {
        return (TextRenderData) p.getContents().get(0);
    }
}
