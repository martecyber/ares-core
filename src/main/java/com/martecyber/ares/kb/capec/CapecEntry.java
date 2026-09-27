package com.martecyber.ares.kb.capec;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Postgres-backed CAPEC record (AQL-wide initiative, Phase 5 — migrated off MongoDB's {@code
 * kb_capec}). Flat-string lists ({@code prerequisites}/{@code mitigations}/{@code
 * relatedCweIds}/{@code relatedAttackTechniqueIds}/{@code parentCapecIds}/{@code
 * childCapecIds}/{@code domains}) are native {@code text[]} columns (HAS-queryable, GIN-indexed).
 * {@code consequences}/{@code executionFlow} stay JSONB — display-only structured data, same
 * convention {@link com.martecyber.ares.kb.cwe.CweEntry} already established for this shape.
 */
@Entity
@Table(name = "capec", schema = "ares")
public class CapecEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capec_id", nullable = false, unique = true)
    private String capecId;

    /** Full official code ("CAPEC-63"), unlike the bare-numeric capecId ("63") — mirrors {@link
     *  com.martecyber.ares.kb.cwe.CweEntry#getCode()}, added (V158) so AQL's capec.id can expose
     *  the full code the same way cwe.id/cve.id already do. */
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    private String name;
    private String abstraction; // Meta, Standard, Detailed
    private String status;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "extended_description", columnDefinition = "text")
    private String extendedDescription;

    @Column(name = "typical_severity")
    private String typicalSeverity;

    @Column(name = "likelihood_of_attack")
    private String likelihoodOfAttack;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] prerequisites = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] mitigations = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String consequences = "[]";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related_cwe_ids", columnDefinition = "text[]")
    private String[] relatedCweIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related_attack_technique_ids", columnDefinition = "text[]")
    private String[] relatedAttackTechniqueIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "parent_capec_ids", columnDefinition = "text[]")
    private String[] parentCapecIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "child_capec_ids", columnDefinition = "text[]")
    private String[] childCapecIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] domains = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_flow", nullable = false, columnDefinition = "jsonb")
    private String executionFlow = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_attack_patterns", nullable = false, columnDefinition = "jsonb")
    private String relatedAttackPatterns = "[]";

    // Column named "refs" rather than "references" — reserved SQL keyword, same precedent as
    // CveEntry/CweEntry.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refs", nullable = false, columnDefinition = "jsonb")
    private String references = "[]";

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "source_version")
    private String sourceVersion;

    // ── Nested types ─────────────────────────────────────────────────────

    public record Consequence(List<String> scopes, List<String> impacts, String likelihood, String note) {}

    public record AttackStep(int step, String phase, String description, List<String> techniques) {}

    /** One MITRE Related_Attack_Pattern edge, every Nature ("ChildOf"/"ParentOf"/"PeerOf"/
     *  "CanPrecede"/"CanFollow"/"CanAlsoBe"), not just the two that also feed {@link
     *  #parentCapecIds}/{@link #childCapecIds} for AQL. */
    public record RelatedPattern(String capecId, String nature) {}

    public record Reference(String url, String name) {}

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getCapecId() { return capecId; }
    public void setCapecId(String capecId) { this.capecId = capecId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAbstraction() { return abstraction; }
    public void setAbstraction(String abstraction) { this.abstraction = abstraction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExtendedDescription() { return extendedDescription; }
    public void setExtendedDescription(String extendedDescription) { this.extendedDescription = extendedDescription; }

    public String getTypicalSeverity() { return typicalSeverity; }
    public void setTypicalSeverity(String typicalSeverity) { this.typicalSeverity = typicalSeverity; }

    public String getLikelihoodOfAttack() { return likelihoodOfAttack; }
    public void setLikelihoodOfAttack(String likelihoodOfAttack) { this.likelihoodOfAttack = likelihoodOfAttack; }

    public List<String> getPrerequisites() { return List.of(prerequisites); }
    public void setPrerequisites(List<String> v) { this.prerequisites = toArray(v); }

    public List<String> getMitigations() { return List.of(mitigations); }
    public void setMitigations(List<String> v) { this.mitigations = toArray(v); }

    public List<Consequence> getConsequences() { return parseList(consequences, Consequence[].class); }
    public void setConsequences(List<Consequence> v) { this.consequences = toJson(v); }

    public List<String> getRelatedCweIds() { return List.of(relatedCweIds); }
    public void setRelatedCweIds(List<String> v) { this.relatedCweIds = toArray(v); }

    public List<String> getRelatedAttackTechniqueIds() { return List.of(relatedAttackTechniqueIds); }
    public void setRelatedAttackTechniqueIds(List<String> v) { this.relatedAttackTechniqueIds = toArray(v); }

    public List<String> getParentCapecIds() { return List.of(parentCapecIds); }
    public void setParentCapecIds(List<String> v) { this.parentCapecIds = toArray(v); }

    public List<String> getChildCapecIds() { return List.of(childCapecIds); }
    public void setChildCapecIds(List<String> v) { this.childCapecIds = toArray(v); }

    public List<String> getDomains() { return List.of(domains); }
    public void setDomains(List<String> v) { this.domains = toArray(v); }

    public List<AttackStep> getExecutionFlow() { return parseList(executionFlow, AttackStep[].class); }
    public void setExecutionFlow(List<AttackStep> v) { this.executionFlow = toJson(v); }

    public List<RelatedPattern> getRelatedAttackPatterns() { return parseList(relatedAttackPatterns, RelatedPattern[].class); }
    public void setRelatedAttackPatterns(List<RelatedPattern> v) { this.relatedAttackPatterns = toJson(v); }

    public List<Reference> getReferences() { return parseList(references, Reference[].class); }
    public void setReferences(List<Reference> v) { this.references = toJson(v); }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    // ── JSON / array helpers ────────────────────────────────────────

    private static String[] toArray(List<String> v) {
        return v == null ? new String[0] : v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }

    private <T> List<T> parseList(String json, Class<T[]> arrayType) {
        try { return java.util.Arrays.asList(MAPPER.readValue(json, arrayType)); }
        catch (Exception e) { throw new IllegalStateException("Corrupt JSON list for CAPEC " + capecId, e); }
    }

    private static String toJson(Object v) {
        try { return MAPPER.writeValueAsString(v); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize CAPEC field", e); }
    }
}
