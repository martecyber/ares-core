package com.martecyber.ares.kb.capec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the CVE/CWE-parity detail-view redesign's CAPEC additions: every
 * Related_Attack_Pattern Nature (not just ChildOf/ParentOf, mirroring CweXmlParserTest's
 * equivalent case) and per-pattern URL references resolved against the document's own
 * <External_References> catalog.
 */
class CapecXmlParserTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Attack_Pattern_Catalog Version="3.9">
          <Attack_Patterns>
            <Attack_Pattern ID="63" Name="Cross-Site Scripting (XSS)" Abstraction="Standard" Status="Draft">
              <Description>Base description.</Description>
              <Related_Attack_Patterns>
                <Related_Attack_Pattern Nature="ChildOf" CAPEC_ID="588"/>
                <Related_Attack_Pattern Nature="PeerOf" CAPEC_ID="19"/>
                <Related_Attack_Pattern Nature="CanPrecede" CAPEC_ID="242"/>
              </Related_Attack_Patterns>
              <References>
                <Reference Reference_ID="REF-1"/>
              </References>
            </Attack_Pattern>
          </Attack_Patterns>
          <External_References>
            <External_Reference Reference_ID="REF-1">
              <Author>Jane Doe</Author>
              <Title>A Paper On XSS</Title>
              <URL>https://example.org/xss-paper</URL>
            </External_Reference>
          </External_References>
        </Attack_Pattern_Catalog>
        """;

    private CapecEntry parseOne() throws Exception {
        CapecXmlParser parser = new CapecXmlParser();
        var result = parser.parse(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(1, result.size());
        return result.get(0);
    }

    @Test
    void relatedAttackPatternsCaptureEveryNatureNotJustParentChild() throws Exception {
        CapecEntry e = parseOne();
        assertEquals(3, e.getRelatedAttackPatterns().size());
        assertTrue(e.getRelatedAttackPatterns().contains(new CapecEntry.RelatedPattern("588", "ChildOf")));
        assertTrue(e.getRelatedAttackPatterns().contains(new CapecEntry.RelatedPattern("19", "PeerOf")));
        assertTrue(e.getRelatedAttackPatterns().contains(new CapecEntry.RelatedPattern("242", "CanPrecede")));
        // ChildOf/ParentOf still feed the legacy AQL-queryable array columns too.
        assertEquals(List.of("588"), e.getParentCapecIds());
    }

    @Test
    void referencesResolveAgainstTheExternalReferencesCatalog() throws Exception {
        CapecEntry.Reference r = parseOne().getReferences().get(0);
        assertEquals("https://example.org/xss-paper", r.url());
        assertEquals("A Paper On XSS", r.name());
    }
}
