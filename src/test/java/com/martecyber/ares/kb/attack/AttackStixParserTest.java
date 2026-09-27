package com.martecyber.ares.kb.attack;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the Phase 5 (AQL-wide initiative) addition of STIX {@code relationship}
 * object parsing to {@link AttackStixParser} — previously discarded entirely (no {@code case
 * "relationship"} existed in {@link AttackStixParser#parse}), the concrete new capability this
 * phase's plan explicitly called "the new STIX relationship parsing" for mitigation↔technique
 * linkage. Exercises a small synthetic STIX 2.1 bundle shaped like MITRE's real enterprise-attack
 * bundle: one tactic, one technique, one mitigation, and a {@code "mitigates"} relationship
 * between them — plus decoy relationship types/directions that must NOT be picked up.
 */
class AttackStixParserTest {

    private static final String BUNDLE = """
        {
          "type": "bundle",
          "objects": [
            {
              "type": "x-mitre-tactic",
              "id": "x-mitre-tactic--aaaa",
              "name": "Initial Access",
              "x_mitre_shortname": "initial-access",
              "description": "The adversary is trying to get into your network."
            },
            {
              "type": "x-mitre-matrix",
              "tactic_refs": ["x-mitre-tactic--aaaa"]
            },
            {
              "type": "attack-pattern",
              "id": "attack-pattern--bbbb",
              "name": "Phishing",
              "description": "Adversaries may send phishing messages.",
              "kill_chain_phases": [{ "kill_chain_name": "mitre-attack", "phase_name": "initial-access" }],
              "external_references": [{ "source_name": "mitre-attack", "external_id": "T1566" }]
            },
            {
              "type": "course-of-action",
              "id": "course-of-action--cccc",
              "name": "User Training",
              "description": "Train users to spot phishing.",
              "external_references": [{ "source_name": "mitre-attack", "external_id": "M1017" }]
            },
            {
              "type": "relationship",
              "id": "relationship--dddd",
              "relationship_type": "mitigates",
              "source_ref": "course-of-action--cccc",
              "target_ref": "attack-pattern--bbbb"
            },
            {
              "type": "relationship",
              "id": "relationship--eeee",
              "relationship_type": "uses",
              "source_ref": "intrusion-set--ffff",
              "target_ref": "attack-pattern--bbbb"
            },
            {
              "type": "relationship",
              "id": "relationship--gggg",
              "relationship_type": "mitigates",
              "source_ref": "attack-pattern--bbbb",
              "target_ref": "course-of-action--cccc"
            }
          ]
        }
        """;

    private AttackStixParser.ParseResult parse() throws Exception {
        AttackStixParser parser = new AttackStixParser();
        return parser.parse(new ByteArrayInputStream(BUNDLE.getBytes(StandardCharsets.UTF_8)), "enterprise-attack", null);
    }

    @Test
    void tacticsTechniquesAndMitigationsAreStillParsedCorrectly() throws Exception {
        var result = parse();
        assertEquals(1, result.tactics().size());
        assertEquals(1, result.techniques().size());
        assertEquals(1, result.mitigations().size());
        assertEquals("initial-access", result.tactics().get(0).getShortName());
        assertEquals("T1566", result.techniques().get(0).getAttackId());
        assertEquals("M1017", result.mitigations().get(0).getAttackId());
    }

    /** The core regression case: the "mitigates" relationship (course-of-action -> attack-pattern)
     *  must be captured, and captured with the right (mitigationStixId, techniqueStixId) pairing. */
    @Test
    void mitigatesRelationshipIsCaptured() throws Exception {
        var result = parse();
        assertEquals(1, result.mitigatesRelationships().size());
        var rel = result.mitigatesRelationships().get(0);
        assertEquals("course-of-action--cccc", rel.mitigationStixId());
        assertEquals("attack-pattern--bbbb", rel.techniqueStixId());
    }

    /** Two decoys in the same bundle that must NOT be picked up: a "uses" relationship (wrong
     *  relationship_type) and a "mitigates" relationship with source/target REVERSED (attack-pattern
     *  mitigating a course-of-action makes no sense and shouldn't happen in real data, but the
     *  parser must reject it by STIX type prefix regardless of relationship_type matching). */
    @Test
    void nonMitigatesAndReversedRelationshipsAreIgnored() throws Exception {
        var result = parse();
        // Only the one correctly-shaped relationship survives — not 3.
        assertEquals(1, result.mitigatesRelationships().size());
    }

    @Test
    void emptyRelationshipsListWhenBundleHasNone() throws Exception {
        String noRelationships = """
            { "type": "bundle", "objects": [
              { "type": "attack-pattern", "id": "attack-pattern--x", "name": "T",
                "external_references": [{ "source_name": "mitre-attack", "external_id": "T9999" }] }
            ]}
            """;
        AttackStixParser parser = new AttackStixParser();
        var result = parser.parse(new ByteArrayInputStream(noRelationships.getBytes(StandardCharsets.UTF_8)), "enterprise-attack", null);
        assertTrue(result.mitigatesRelationships().isEmpty());
    }

    /** Regression for the CVE/CWE/CAPEC-parity redesign's References card: every
     *  external_references entry with a URL is kept (the technique's own attack.mitre.org page,
     *  which has no description, plus an inline citation, which does) — previously only the
     *  bare external_id off the mitre-attack source was ever read. */
    @Test
    void referencesAreExtractedFromExternalReferences() throws Exception {
        String bundle = """
            { "type": "bundle", "objects": [
              { "type": "attack-pattern", "id": "attack-pattern--x", "name": "Phishing",
                "external_references": [
                  { "source_name": "mitre-attack", "external_id": "T1566", "url": "https://attack.mitre.org/techniques/T1566" },
                  { "source_name": "Some Vendor", "description": "Some Vendor Blog Post", "url": "https://example.org/blog" }
                ] }
            ]}
            """;
        AttackStixParser parser = new AttackStixParser();
        var result = parser.parse(new ByteArrayInputStream(bundle.getBytes(StandardCharsets.UTF_8)), "enterprise-attack", null);
        var refs = result.techniques().get(0).getReferences();
        assertEquals(2, refs.size());
        assertEquals("https://attack.mitre.org/techniques/T1566", refs.get(0).url());
        assertEquals("mitre-attack", refs.get(0).name()); // no description on this one — falls back to source_name
        assertEquals("https://example.org/blog", refs.get(1).url());
        assertEquals("Some Vendor Blog Post", refs.get(1).name());
    }
}
