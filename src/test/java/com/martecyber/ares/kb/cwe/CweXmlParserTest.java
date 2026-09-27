package com.martecyber.ares.kb.cwe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the Phase 5 (AQL-wide initiative) rewrite of {@link CweXmlParser} from
 * mutate-the-live-getter-result ({@code current.getParentIds().add(id)}, which relied on the old
 * Mongo-era {@code CweEntry} returning a plain mutable {@code ArrayList} field) to accumulate-in-a-
 * local-then-commit-via-setter ({@code CweEntry}'s new Postgres-backed array/JSONB getters each
 * return a fresh immutable snapshot, so the old mutation pattern would throw {@code
 * UnsupportedOperationException} at parse time). Exercises every list-typed field the rewrite
 * touched, from a small synthetic CWE XML fragment shaped like MITRE's real schema.
 */
class CweXmlParserTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Weakness_Catalog Version="4.13">
          <Weaknesses>
            <Weakness ID="79" Name="Cross-site Scripting" Abstraction="Base" Status="Stable">
              <Description>Improper neutralization of input.</Description>
              <Extended_Description>Longer description text.</Extended_Description>
              <Related_Weaknesses>
                <Related_Weakness Nature="ChildOf" CWE_ID="74"/>
                <Related_Weakness Nature="ParentOf" CWE_ID="80"/>
              </Related_Weaknesses>
              <Applicable_Platforms>
                <Language Name="PHP"/>
                <Technology Class="Web Server"/>
              </Applicable_Platforms>
              <Common_Consequences>
                <Consequence>
                  <Scope>Confidentiality</Scope>
                  <Scope>Integrity</Scope>
                  <Impact>Read Application Data</Impact>
                  <Note>Some note.</Note>
                </Consequence>
              </Common_Consequences>
              <Potential_Mitigations>
                <Mitigation>
                  <Phase>Architecture and Design</Phase>
                  <Strategy>Input Validation</Strategy>
                  <Description>Validate all input.</Description>
                  <Effectiveness>High</Effectiveness>
                </Mitigation>
              </Potential_Mitigations>
              <Related_Attack_Patterns>
                <Related_Attack_Pattern CAPEC_ID="63"/>
              </Related_Attack_Patterns>
              <Observed_Examples>
                <Observed_Example>
                  <Reference>CVE-2021-1234</Reference>
                </Observed_Example>
              </Observed_Examples>
              <Mapping_Notes>
                <Usage>Allowed</Usage>
                <Rationale>Rationale text.</Rationale>
                <Comments>Comment text.</Comments>
              </Mapping_Notes>
            </Weakness>
          </Weaknesses>
        </Weakness_Catalog>
        """;

    private CweEntry parseOne() throws Exception {
        CweXmlParser parser = new CweXmlParser();
        var result = parser.parse(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(1, result.entries().size());
        assertEquals("4.13", result.version());
        return result.entries().get(0);
    }

    @Test
    void basicScalarFieldsAreParsed() throws Exception {
        CweEntry e = parseOne();
        assertEquals("79", e.getCweId());
        assertEquals("CWE-79", e.getCode());
        assertEquals("Cross-site Scripting", e.getName());
        assertEquals("Weakness", e.getType());
        assertEquals("Base", e.getAbstraction());
        assertEquals("Stable", e.getStatus());
        assertEquals("Improper neutralization of input.", e.getDescription());
        assertEquals("Longer description text.", e.getExtendedDescription());
    }

    /** The core regression case: parentIds/childIds must be populated via accumulate-then-set,
     *  not silently empty because the old mutation pattern threw and got swallowed, or was
     *  never actually wired to a settable list. */
    @Test
    void parentAndChildIdsAreAccumulatedCorrectly() throws Exception {
        CweEntry e = parseOne();
        assertEquals(List.of("74"), e.getParentIds());
        assertEquals(List.of("80"), e.getChildIds());
    }

    @Test
    void relatedCapecIdsAreAccumulated() throws Exception {
        assertEquals(List.of("63"), parseOne().getRelatedCapecIds());
    }

    @Test
    void applicablePlatformsAreAccumulatedFromNameAndClassAttributes() throws Exception {
        CweEntry e = parseOne();
        assertEquals(2, e.getApplicablePlatforms().size());
        assertTrue(e.getApplicablePlatforms().contains("php"));
        assertTrue(e.getApplicablePlatforms().contains("web server"));
    }

    @Test
    void observedExamplesAreAccumulated() throws Exception {
        assertEquals(List.of("cve-2021-1234"), parseOne().getObservedExamples());
    }

    @Test
    void consequencesRoundTripThroughJsonb() throws Exception {
        CweEntry e = parseOne();
        assertEquals(1, e.getConsequences().size());
        CweEntry.Consequence c = e.getConsequences().get(0);
        assertEquals(List.of("Confidentiality", "Integrity"), c.scopes());
        assertEquals(List.of("Read Application Data"), c.impacts());
        assertEquals("Some note.", c.note());
    }

    @Test
    void mitigationsRoundTripThroughJsonb() throws Exception {
        CweEntry e = parseOne();
        assertEquals(1, e.getMitigations().size());
        CweEntry.Mitigation m = e.getMitigations().get(0);
        assertEquals(List.of("Architecture and Design"), m.phases());
        assertEquals("Input Validation", m.strategy());
        assertEquals("Validate all input.", m.description());
        assertEquals("High", m.effectiveness());
    }

    @Test
    void vulnerabilityMappingRoundTripsThroughJsonb() throws Exception {
        CweEntry e = parseOne();
        assertNotNull(e.getVulnerabilityMapping());
        assertEquals("Allowed", e.getVulnerabilityMapping().usage());
        assertEquals("Rationale text.", e.getVulnerabilityMapping().rationale());
        assertEquals("Comment text.", e.getVulnerabilityMapping().comments());
    }

    /** Regression test for a second entry not inheriting the first entry's accumulated lists —
     *  a real risk with the accumulate-in-a-Handler-field rewrite if startEntry() forgot to reset
     *  any one of them. */
    @Test
    void secondEntryDoesNotInheritFirstEntrysLists() throws Exception {
        String twoEntries = XML.replace("</Weaknesses>", """
              <Weakness ID="89" Name="SQL Injection" Abstraction="Base" Status="Stable">
                <Description>desc</Description>
              </Weakness>
            </Weaknesses>""");
        CweXmlParser parser = new CweXmlParser();
        var result = parser.parse(new ByteArrayInputStream(twoEntries.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(2, result.entries().size());
        CweEntry second = result.entries().get(1);
        assertEquals("89", second.getCweId());
        assertTrue(second.getParentIds().isEmpty());
        assertTrue(second.getChildIds().isEmpty());
        assertTrue(second.getRelatedCapecIds().isEmpty());
        assertTrue(second.getApplicablePlatforms().isEmpty());
        assertTrue(second.getObservedExamples().isEmpty());
        assertTrue(second.getConsequences().isEmpty());
        assertTrue(second.getMitigations().isEmpty());
    }

    // ── Fields added for the CVE-parity detail-view redesign: every Related_Weakness Nature,
    // weakness-level URL references (resolved post-parse against the trailing External_References
    // catalog), Category/View memberships (inverted from Has_Member, segregated by View), Notes,
    // Alternate_Terms and Detection_Methods. Kept in a separate fixture from the main XML above so
    // extending it can't perturb the assertions those existing tests make.

    private static final String EXTENDED_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Weakness_Catalog Version="4.13">
          <Weaknesses>
            <Weakness ID="20" Name="Improper Input Validation" Abstraction="Class" Status="Stable">
              <Description>Base description.</Description>
              <Related_Weaknesses>
                <Related_Weakness Nature="ChildOf" CWE_ID="707"/>
                <Related_Weakness Nature="PeerOf" CWE_ID="1284"/>
                <Related_Weakness Nature="CanPrecede" CWE_ID="119"/>
              </Related_Weaknesses>
              <Alternate_Terms>
                <Alternate_Term>
                  <Term>Input Validation</Term>
                  <Description>A common shorthand.</Description>
                </Alternate_Term>
              </Alternate_Terms>
              <Notes>
                <Note Type="Relationship">A general relationship note.</Note>
              </Notes>
              <Detection_Methods>
                <Detection_Method>
                  <Method>Automated Static Analysis</Method>
                  <Description>Effective at finding this issue.</Description>
                  <Effectiveness>High</Effectiveness>
                </Detection_Method>
              </Detection_Methods>
              <References>
                <Reference External_Reference_ID="REF-1"/>
              </References>
            </Weakness>
          </Weaknesses>
          <Categories>
            <Category ID="700" Name="Seven Pernicious Kingdoms" Status="Obsolete">
              <Relationships>
                <Has_Member CWE_ID="20" View_ID="700"/>
              </Relationships>
            </Category>
          </Categories>
          <External_References>
            <External_Reference Reference_ID="REF-1">
              <Author>Jane Doe</Author>
              <Title>A Paper On Input Validation</Title>
              <URL>https://example.org/paper</URL>
            </External_Reference>
          </External_References>
        </Weakness_Catalog>
        """;

    private CweEntry parseExtended() throws Exception {
        CweXmlParser parser = new CweXmlParser();
        var result = parser.parse(new ByteArrayInputStream(EXTENDED_XML.getBytes(StandardCharsets.UTF_8)), null);
        return result.entries().stream().filter(e -> "20".equals(e.getCweId())).findFirst().orElseThrow();
    }

    @Test
    void relatedWeaknessesCaptureEveryNatureNotJustParentChild() throws Exception {
        CweEntry e = parseExtended();
        assertEquals(3, e.getRelatedWeaknesses().size());
        assertTrue(e.getRelatedWeaknesses().contains(new CweEntry.RelatedWeakness("707", "ChildOf", null, null)));
        assertTrue(e.getRelatedWeaknesses().contains(new CweEntry.RelatedWeakness("1284", "PeerOf", null, null)));
        assertTrue(e.getRelatedWeaknesses().contains(new CweEntry.RelatedWeakness("119", "CanPrecede", null, null)));
        // ChildOf/ParentOf still feed the legacy AQL-queryable array columns too.
        assertEquals(List.of("707"), e.getParentIds());
    }

    @Test
    void relatedWeaknessViewIdResolvesToAViewNameFromElsewhereInTheDocument() throws Exception {
        String xml = EXTENDED_XML.replace(
            "<Related_Weakness Nature=\"ChildOf\" CWE_ID=\"707\"/>",
            "<Related_Weakness Nature=\"ChildOf\" CWE_ID=\"707\" View_ID=\"700\"/>");
        CweXmlParser parser = new CweXmlParser();
        var result = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), null);
        CweEntry e = result.entries().stream().filter(x -> "20".equals(x.getCweId())).findFirst().orElseThrow();
        CweEntry.RelatedWeakness rw = e.getRelatedWeaknesses().stream().filter(r -> "707".equals(r.cweId())).findFirst().orElseThrow();
        assertEquals("700", rw.viewId());
        assertEquals("Seven Pernicious Kingdoms", rw.viewName());
    }

    @Test
    void alternateTermsAreAccumulated() throws Exception {
        CweEntry.AlternateTerm t = parseExtended().getAlternateTerms().get(0);
        assertEquals("Input Validation", t.term());
        assertEquals("A common shorthand.", t.description());
    }

    @Test
    void notesAreAccumulated() throws Exception {
        CweEntry.Note n = parseExtended().getNotes().get(0);
        assertEquals("Relationship", n.type());
        assertEquals("A general relationship note.", n.text());
    }

    @Test
    void detectionMethodsAreAccumulated() throws Exception {
        CweEntry.DetectionMethod d = parseExtended().getDetectionMethods().get(0);
        assertEquals("Automated Static Analysis", d.method());
        assertEquals("Effective at finding this issue.", d.description());
        assertEquals("High", d.effectiveness());
    }

    @Test
    void weaknessLevelReferencesResolveAgainstTheExternalReferencesCatalog() throws Exception {
        CweEntry.Reference r = parseExtended().getReferences().get(0);
        assertEquals("https://example.org/paper", r.url());
        assertEquals("A Paper On Input Validation", r.name());
    }

    @Test
    void membershipsAreInvertedFromTheCategorysHasMemberEdges() throws Exception {
        CweEntry.Membership m = parseExtended().getMemberships().get(0);
        assertEquals("700", m.viewId());
        assertEquals("700", m.categoryId());
        assertEquals("Seven Pernicious Kingdoms", m.categoryName());
        assertEquals("Seven Pernicious Kingdoms", m.viewName()); // View_ID happens to equal this fixture's own Category ID
    }
}
