package com.martecyber.ares.kb.cwe;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Postgres-backed CWE record (AQL-wide initiative, Phase 5 — migrated off MongoDB's {@code
 * kb_cwe}). {@code parentIds}/{@code childIds}/{@code relatedCapecIds}/{@code applicablePlatforms}/
 * {@code observedExamples} are native {@code text[]} columns (HAS-queryable, GIN-indexed).
 * {@code consequences}/{@code mitigations}/{@code vulnerabilityMapping} stay JSONB — display-only
 * structured data with no AQL query requirement — exposed through ordinary typed getters/setters
 * (parsing/serializing the underlying JSON string field on the fly), same convention {@link
 * com.martecyber.ares.kb.cve.CveEntry} already established, so this entity's public shape (and
 * therefore its REST JSON shape) is unchanged from the pre-migration Mongo document.
 */
@Entity
@Table(name = "cwe", schema = "ares")
public class CweEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cwe_id", nullable = false, unique = true)
    private String cweId;

    private String code;
    private String name;
    private String type; // Weakness, Category, View, Compound
    private String abstraction; // Pillar, Class, Base, Variant, Compound
    private String status;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "extended_description", columnDefinition = "text")
    private String extendedDescription;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String consequences = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String mitigations = "[]";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "parent_ids", columnDefinition = "text[]")
    private String[] parentIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "child_ids", columnDefinition = "text[]")
    private String[] childIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related_capec_ids", columnDefinition = "text[]")
    private String[] relatedCapecIds = new String[0];

    @Column(name = "likelihood_of_exploit")
    private String likelihoodOfExploit;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "applicable_platforms", columnDefinition = "text[]")
    private String[] applicablePlatforms = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "observed_examples", columnDefinition = "text[]")
    private String[] observedExamples = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vulnerability_mapping", columnDefinition = "jsonb")
    private String vulnerabilityMapping;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "source_version")
    private String sourceVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_weaknesses", nullable = false, columnDefinition = "jsonb")
    private String relatedWeaknesses = "[]";

    // Column named "refs" rather than "references" — the latter is a reserved SQL keyword that
    // would need dialect-specific quoting for no real benefit, same precedent as CveEntry.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refs", nullable = false, columnDefinition = "jsonb")
    private String references = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memberships", nullable = false, columnDefinition = "jsonb")
    private String memberships = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", nullable = false, columnDefinition = "jsonb")
    private String notes = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alternate_terms", nullable = false, columnDefinition = "jsonb")
    private String alternateTerms = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_methods", nullable = false, columnDefinition = "jsonb")
    private String detectionMethods = "[]";

    // ── Nested types ──────────────────────────────────────────────

    public record Consequence(List<String> scopes, List<String> impacts, String likelihood, String note) {}

    public record Mitigation(List<String> phases, String strategy, String description, String effectiveness) {}

    public record VulnerabilityMapping(String usage, String rationale, String comments) {}

    /** One MITRE Related_Weakness edge, every Nature ("ChildOf"/"ParentOf"/"PeerOf"/
     *  "CanPrecede"/"CanFollow"/"CanAlsoBe"/"Requires"/"RequiredBy"/"StartsWith"), not just the
     *  two that also feed {@link #parentIds}/{@link #childIds} for AQL. MITRE tags each edge with
     *  the View it was declared under (optional — some edges are view-agnostic); viewName is
     *  resolved post-parse the same way Membership's is. */
    public record RelatedWeakness(String cweId, String nature, String viewId, String viewName) {}

    public record Reference(String url, String name) {}

    /** A Category/View this weakness is a member of, inverted at parse time from that
     *  container's Has_Member edges — MITRE's schema attaches membership to the container
     *  (Category/View), not the weakness, and tags each edge with the View it was declared
     *  under (so the same Category can carry different viewId/viewName rows). */
    public record Membership(String viewId, String viewName, String categoryId, String categoryName) {}

    public record Note(String type, String text) {}

    public record AlternateTerm(String term, String description) {}

    public record DetectionMethod(String method, String description, String effectiveness) {}

    // ── Getters / Setters ─────────────────────────────────────────

    public Long getId() { return id; }

    public String getCweId() { return cweId; }
    public void setCweId(String cweId) { this.cweId = cweId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAbstraction() { return abstraction; }
    public void setAbstraction(String abstraction) { this.abstraction = abstraction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExtendedDescription() { return extendedDescription; }
    public void setExtendedDescription(String extendedDescription) { this.extendedDescription = extendedDescription; }

    public List<Consequence> getConsequences() { return parseList(consequences, Consequence[].class); }
    public void setConsequences(List<Consequence> v) { this.consequences = toJson(v); }

    public List<Mitigation> getMitigations() { return parseList(mitigations, Mitigation[].class); }
    public void setMitigations(List<Mitigation> v) { this.mitigations = toJson(v); }

    public List<String> getParentIds() { return List.of(parentIds); }
    public void setParentIds(List<String> v) { this.parentIds = toArray(v); }

    public List<String> getChildIds() { return List.of(childIds); }
    public void setChildIds(List<String> v) { this.childIds = toArray(v); }

    public List<String> getRelatedCapecIds() { return List.of(relatedCapecIds); }
    public void setRelatedCapecIds(List<String> v) { this.relatedCapecIds = toArray(v); }

    public String getLikelihoodOfExploit() { return likelihoodOfExploit; }
    public void setLikelihoodOfExploit(String likelihoodOfExploit) { this.likelihoodOfExploit = likelihoodOfExploit; }

    public List<String> getApplicablePlatforms() { return List.of(applicablePlatforms); }
    public void setApplicablePlatforms(List<String> v) { this.applicablePlatforms = toArray(v); }

    public List<String> getObservedExamples() { return List.of(observedExamples); }
    public void setObservedExamples(List<String> v) { this.observedExamples = toArray(v); }

    public VulnerabilityMapping getVulnerabilityMapping() {
        if (vulnerabilityMapping == null) return null;
        try { return MAPPER.readValue(vulnerabilityMapping, VulnerabilityMapping.class); }
        catch (Exception e) { throw new IllegalStateException("Corrupt vulnerabilityMapping JSON for CWE " + cweId, e); }
    }
    public void setVulnerabilityMapping(VulnerabilityMapping v) { this.vulnerabilityMapping = v == null ? null : toJson(v); }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    public List<RelatedWeakness> getRelatedWeaknesses() { return parseList(relatedWeaknesses, RelatedWeakness[].class); }
    public void setRelatedWeaknesses(List<RelatedWeakness> v) { this.relatedWeaknesses = toJson(v); }

    public List<Reference> getReferences() { return parseList(references, Reference[].class); }
    public void setReferences(List<Reference> v) { this.references = toJson(v); }

    public List<Membership> getMemberships() { return parseList(memberships, Membership[].class); }
    public void setMemberships(List<Membership> v) { this.memberships = toJson(v); }

    public List<Note> getNotes() { return parseList(notes, Note[].class); }
    public void setNotes(List<Note> v) { this.notes = toJson(v); }

    public List<AlternateTerm> getAlternateTerms() { return parseList(alternateTerms, AlternateTerm[].class); }
    public void setAlternateTerms(List<AlternateTerm> v) { this.alternateTerms = toJson(v); }

    public List<DetectionMethod> getDetectionMethods() { return parseList(detectionMethods, DetectionMethod[].class); }
    public void setDetectionMethods(List<DetectionMethod> v) { this.detectionMethods = toJson(v); }

    // ── JSON / array helpers ────────────────────────────────────────

    private static String[] toArray(List<String> v) {
        return v == null ? new String[0] : v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }

    private <T> List<T> parseList(String json, Class<T[]> arrayType) {
        try { return java.util.Arrays.asList(MAPPER.readValue(json, arrayType)); }
        catch (Exception e) { throw new IllegalStateException("Corrupt JSON list for CWE " + cweId, e); }
    }

    private static String toJson(Object v) {
        try { return MAPPER.writeValueAsString(v); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize CWE field", e); }
    }
}
