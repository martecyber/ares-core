package com.martecyber.ares.imports.parsers;

import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.imports.AssetImportHelper;
import com.martecyber.ares.imports.ParseResult;
import com.martecyber.ares.imports.ParsedAsset;
import com.martecyber.ares.imports.ParsedAssetLink;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the scanner-parser helpers shared by every network/tool parser in this package —
 * host/service asset-chain emission, CVSS bucketing, the RFC4180 CSV tokenizer, and the small
 * IP/JSONL/URL utilities. These are pure functions with no Spring context, so every edge case
 * worth a parser bug is cheap to pin down here directly.
 */
class ScannerParserUtilsTest {

    // ── isIp / isIpv6 ────────────────────────────────────────────────

    @Test
    void isIpAcceptsOnlyDottedQuadStrings() {
        assertTrue(ScannerParserUtils.isIp("192.168.1.1"));
        assertTrue(ScannerParserUtils.isIp("  10.0.0.1  ".trim()));
        assertFalse(ScannerParserUtils.isIp("not-an-ip"));
        assertFalse(ScannerParserUtils.isIp("2001:db8::1"));
        assertFalse(ScannerParserUtils.isIp(null));
        // The pattern is purely shape-based — it doesn't validate octet ranges.
        assertTrue(ScannerParserUtils.isIp("999.999.999.999"));
    }

    @Test
    void isIpv6AcceptsOnlyRealIpv6Addresses() {
        assertTrue(ScannerParserUtils.isIpv6("2001:db8::1"));
        assertTrue(ScannerParserUtils.isIpv6("::1"));
        assertFalse(ScannerParserUtils.isIpv6("192.168.1.1"));
        assertFalse(ScannerParserUtils.isIpv6("not-an-ip"));
        assertFalse(ScannerParserUtils.isIpv6(null));
        // No colon at all short-circuits before the (slower) InetAddress lookup.
        assertFalse(ScannerParserUtils.isIpv6("example.com"));
    }

    // ── firstNonEmptyLine ────────────────────────────────────────────

    @Test
    void firstNonEmptyLineSkipsLeadingBlankLines() {
        byte[] content = "\n\n  \n{\"host\":\"a\"}\n{\"host\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"host\":\"a\"}", ScannerParserUtils.firstNonEmptyLine(content));
    }

    @Test
    void firstNonEmptyLineReturnsEmptyStringWhenContentIsAllBlank() {
        assertEquals("", ScannerParserUtils.firstNonEmptyLine("\n\n   \n".getBytes(StandardCharsets.UTF_8)));
    }

    // ── emitHostChain ────────────────────────────────────────────────

    @Test
    void emitHostChainEmitsIpInterfaceHostAndLinksThemInOrder() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        String ifaceId = ScannerParserUtils.emitHostChain("10.0.0.5", result, seen);

        assertEquals("iface-10.0.0.5", ifaceId);
        assertEquals(3, result.getAssets().size());
        assertTrue(result.getAssets().stream().anyMatch(a -> a.getType().equals(AssetType.IP) && a.getIdentifier().equals("10.0.0.5")));
        assertTrue(result.getAssets().stream().anyMatch(a -> a.getType().equals(AssetType.INTERFACE) && a.getIdentifier().equals("iface-10.0.0.5")));
        assertTrue(result.getAssets().stream().anyMatch(a -> a.getType().equals(AssetType.HOST) && a.getIdentifier().equals("host-10.0.0.5")));

        List<ParsedAssetLink> links = result.getLinks();
        assertEquals(2, links.size());
        assertTrue(links.stream().anyMatch(l -> l.getFromIdentifier().equals("host-10.0.0.5")
            && l.getToIdentifier().equals("iface-10.0.0.5") && l.getLinkType().equals(AssetLinkType.HOST_INTERFACE)));
        assertTrue(links.stream().anyMatch(l -> l.getFromIdentifier().equals("iface-10.0.0.5")
            && l.getToIdentifier().equals("10.0.0.5") && l.getLinkType().equals(AssetLinkType.INTERFACE_IP)));
    }

    @Test
    void emitHostChainIsIdempotentForTheSameIpWithinOneSeenSet() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitHostChain("10.0.0.5", result, seen);
        ScannerParserUtils.emitHostChain("10.0.0.5", result, seen);

        // A second call for the same IP must not duplicate assets — only the links repeat
        // (cheap and harmless; ImportService dedups links downstream).
        assertEquals(3, result.getAssets().size());
        assertEquals(4, result.getLinks().size());
    }

    @Test
    void emitHostChainCarriesHostnameAsAHintNotAsTheIdentifier() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitHostChain("10.0.0.5", "web01.example.com", result, seen);

        ParsedAsset host = result.getAssets().stream()
            .filter(a -> a.getType().equals(AssetType.HOST)).findFirst().orElseThrow();
        assertEquals("host-10.0.0.5", host.getIdentifier());
        assertEquals(List.of("web01.example.com"), host.getMetadata().get(AssetImportHelper.HOSTNAME_HINTS_KEY));
    }

    @Test
    void emitHostChainOmitsHostnameHintWhenBlank() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitHostChain("10.0.0.5", "  ", result, seen);

        ParsedAsset host = result.getAssets().stream()
            .filter(a -> a.getType().equals(AssetType.HOST)).findFirst().orElseThrow();
        assertFalse(host.getMetadata().containsKey(AssetImportHelper.HOSTNAME_HINTS_KEY));
    }

    @Test
    void emitHostChainCarriesExternalIdOnlyWhenBothToolAndIdAreGiven() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitHostChain("10.0.0.5", null, "greenbone", "asset-123", result, seen);

        ParsedAsset host = result.getAssets().stream()
            .filter(a -> a.getType().equals(AssetType.HOST)).findFirst().orElseThrow();
        assertEquals("greenbone", host.getMetadata().get(AssetImportHelper.EXTERNAL_ID_TOOL_KEY));
        assertEquals("asset-123", host.getMetadata().get(AssetImportHelper.EXTERNAL_ID_VALUE_KEY));
    }

    @Test
    void emitHostChainOmitsExternalIdWhenOnlyToolIsGiven() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitHostChain("10.0.0.5", null, "greenbone", null, result, seen);

        ParsedAsset host = result.getAssets().stream()
            .filter(a -> a.getType().equals(AssetType.HOST)).findFirst().orElseThrow();
        assertFalse(host.getMetadata().containsKey(AssetImportHelper.EXTERNAL_ID_TOOL_KEY));
        assertFalse(host.getMetadata().containsKey(AssetImportHelper.EXTERNAL_ID_VALUE_KEY));
    }

    // ── emitService ──────────────────────────────────────────────────

    @Test
    void emitServiceBuildsIdentifierAndDefaultsStateToOpen() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        String svcId = ScannerParserUtils.emitService("10.0.0.5", 443, "TCP", "https", result, seen);

        assertEquals("10.0.0.5:443/tcp", svcId);
        ParsedAsset svc = result.getAssets().get(0);
        assertEquals(AssetType.SERVICE, svc.getType());
        assertEquals(443, svc.getMetadata().get("port"));
        assertEquals("tcp", svc.getMetadata().get("protocol"));
        assertEquals("https", svc.getMetadata().get("service"));
        assertEquals("OPEN", svc.getMetadata().get(ScannerParserUtils.VISIBILITY_STATE_KEY));
        assertTrue(result.getLinks().stream().anyMatch(l ->
            l.getFromIdentifier().equals("iface-10.0.0.5") && l.getToIdentifier().equals(svcId)
                && l.getLinkType().equals(AssetLinkType.INTERFACE_SERVICE)));
    }

    @Test
    void emitServiceOmitsBannerWhenBlankAndHonorsExplicitState() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitService("10.0.0.5", 21, "tcp", "  ", "FILTERED", result, seen);

        ParsedAsset svc = result.getAssets().get(0);
        assertFalse(svc.getMetadata().containsKey("service"));
        assertEquals("FILTERED", svc.getMetadata().get(ScannerParserUtils.VISIBILITY_STATE_KEY));
    }

    @Test
    void emitServiceIsIdempotentPerServiceIdentifier() {
        ParseResult result = new ParseResult();
        Set<String> seen = new HashSet<>();

        ScannerParserUtils.emitService("10.0.0.5", 80, "tcp", "http", result, seen);
        ScannerParserUtils.emitService("10.0.0.5", 80, "tcp", "http", result, seen);

        assertEquals(1, result.getAssets().size());
    }

    // ── forEachJsonLine ──────────────────────────────────────────────

    @Test
    void forEachJsonLineSkipsBlankAndNonObjectLines() {
        byte[] content = ("\n  \n{\"a\":1}\nnot-json\n{\"b\":2}\n").getBytes(StandardCharsets.UTF_8);
        List<String> seenLines = new java.util.ArrayList<>();
        ScannerParserUtils.forEachJsonLine(content, seenLines::add);
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), seenLines);
    }

    @Test
    void forEachJsonLineSwallowsAConsumerExceptionButAbortsRemainingLines() {
        // The try/catch wraps the whole read loop, not each iteration individually — a throwing
        // line never propagates (the call itself never throws), but it also silently stops the
        // scan, so every line after the failing one is skipped. Callers that need one bad entry
        // to not blot out the rest of the file (see DnsxParser.processEntry) must catch inside
        // their own consumer lambda instead of relying on this method for per-line isolation.
        byte[] content = "{\"a\":1}\n{\"b\":2}\n".getBytes(StandardCharsets.UTF_8);
        List<String> processed = new java.util.ArrayList<>();
        assertDoesNotThrow(() -> ScannerParserUtils.forEachJsonLine(content, line -> {
            if (line.contains("\"a\"")) throw new RuntimeException("boom");
            processed.add(line);
        }));
        assertEquals(List.of(), processed);
    }

    // ── cvssToSeverity ───────────────────────────────────────────────

    @Test
    void cvssToSeverityBucketsScoresPerThePriorityScale() {
        assertEquals("critical", ScannerParserUtils.cvssToSeverity(9.8, "info"));
        assertEquals("critical", ScannerParserUtils.cvssToSeverity(9.0, "info"));
        assertEquals("high",     ScannerParserUtils.cvssToSeverity(8.9, "info"));
        assertEquals("high",     ScannerParserUtils.cvssToSeverity(7.0, "info"));
        assertEquals("medium",   ScannerParserUtils.cvssToSeverity(6.9, "info"));
        assertEquals("medium",   ScannerParserUtils.cvssToSeverity(4.0, "info"));
        assertEquals("low",      ScannerParserUtils.cvssToSeverity(3.9, "info"));
        assertEquals("low",      ScannerParserUtils.cvssToSeverity(0.1, "info"));
    }

    @Test
    void cvssToSeverityUsesTheCallerSuppliedZeroFallbackInsteadOfDelegating() {
        // score <= 0 never reaches SeverityThresholds — the caller's fallback wins verbatim.
        assertEquals("info", ScannerParserUtils.cvssToSeverity(0.0, "info"));
        assertEquals("low",  ScannerParserUtils.cvssToSeverity(0.0, "low"));
        assertEquals("info", ScannerParserUtils.cvssToSeverity(-1.0, "info"));
    }

    // ── parseCsv ─────────────────────────────────────────────────────

    @Test
    void parseCsvSplitsSimpleUnquotedRecords() {
        List<String[]> rows = ScannerParserUtils.parseCsv("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"a", "b", "c"}, rows.get(0));
        assertArrayEquals(new String[]{"1", "2", "3"}, rows.get(1));
    }

    @Test
    void parseCsvHandlesQuotedFieldsWithEmbeddedCommasNewlinesAndEscapedQuotes() {
        String csv = "name,note\n\"Doe, John\",\"multi\nline\"\n\"He said \"\"hi\"\"\",plain\n";
        List<String[]> rows = ScannerParserUtils.parseCsv(csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, rows.size());
        assertArrayEquals(new String[]{"name", "note"}, rows.get(0));
        assertArrayEquals(new String[]{"Doe, John", "multi\nline"}, rows.get(1));
        assertArrayEquals(new String[]{"He said \"hi\"", "plain"}, rows.get(2));
    }

    @Test
    void parseCsvHandlesMissingTrailingNewline() {
        List<String[]> rows = ScannerParserUtils.parseCsv("a,b\n1,2".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"1", "2"}, rows.get(1));
    }

    @Test
    void parseCsvSkipsBlankLines() {
        List<String[]> rows = ScannerParserUtils.parseCsv("a,b\n\n1,2\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, rows.size());
    }

    // ── baseUrl ──────────────────────────────────────────────────────

    @Test
    void baseUrlStripsPathAndQuery() {
        assertEquals("https://example.com", ScannerParserUtils.baseUrl("https://example.com/a/b?c=1"));
        assertEquals("http://example.com:8080", ScannerParserUtils.baseUrl("http://example.com:8080/path"));
    }

    @Test
    void baseUrlHandlesEdgeCases() {
        assertNull(ScannerParserUtils.baseUrl(null));
        assertNull(ScannerParserUtils.baseUrl(""));
        // Unparsable/host-less input falls back to the trimmed original string rather than throwing.
        assertEquals("not a url", ScannerParserUtils.baseUrl("  not a url  "));
    }
}
