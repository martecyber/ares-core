package com.martecyber.ares.kb.attack;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/** Postgres-backed ATT&CK technique record (AQL-wide initiative, Phase 5 — migrated off
 *  MongoDB's {@code kb_attack_techniques}). {@code tactics} stays a native {@code text[]} column
 *  holding tactic SHORT NAMES (e.g. "initial-access") — kept exactly as before migration since
 *  ares-ui reads it directly (KbAttackView.vue's matrix-by-tactic grouping); {@link
 *  #getPlatforms()}/{@link #getDataSources()}/{@link #getPermissionsRequired()} are likewise
 *  native arrays, all HAS-queryable now that they're real Postgres arrays. The normalized {@code
 *  attack_technique_tactic} join table (see V155's migration comment) is an ADDITIONAL relational
 *  path for AQL nesting ({@code attack.tactics.name}), not a replacement for this array. */
@Entity
@Table(name = "attack_technique", schema = "ares")
public class AttackTechnique {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stix_id")
    private String stixId;

    @Column(name = "attack_id", nullable = false)
    private String attackId;

    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String matrix;

    @Column(name = "is_subtechnique", nullable = false)
    private boolean subtechnique;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] tactics = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] platforms = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "data_sources", columnDefinition = "text[]")
    private String[] dataSources = new String[0];

    @Column(columnDefinition = "text")
    private String detection;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "permissions_required", columnDefinition = "text[]")
    private String[] permissionsRequired = new String[0];

    @Column(nullable = false)
    private boolean deprecated;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "synced_at")
    private Instant syncedAt;

    // Column named "refs" rather than "references" — reserved SQL keyword, same precedent as
    // CveEntry/CweEntry/CapecEntry. Every STIX external_references entry with a URL (the
    // technique's own attack.mitre.org page plus every inline "(Citation: Name)" the description
    // references), same idea as CWE's/CAPEC's References card.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refs", nullable = false, columnDefinition = "jsonb")
    private String references = "[]";

    public record Reference(String url, String name) {}

    public List<Reference> getReferences() {
        try { return java.util.Arrays.asList(MAPPER.readValue(references, Reference[].class)); }
        catch (Exception e) { throw new IllegalStateException("Corrupt references JSON for ATT&CK technique " + attackId, e); }
    }
    public void setReferences(List<Reference> v) {
        try { this.references = MAPPER.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize ATT&CK technique references", e); }
    }

    public Long getId() { return id; }
    public String getStixId() { return stixId; }
    public void setStixId(String stixId) { this.stixId = stixId; }
    public String getAttackId() { return attackId; }
    public void setAttackId(String attackId) { this.attackId = attackId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMatrix() { return matrix; }
    public void setMatrix(String matrix) { this.matrix = matrix; }
    public boolean isSubtechnique() { return subtechnique; }
    public void setSubtechnique(boolean subtechnique) { this.subtechnique = subtechnique; }

    public List<String> getTactics() { return List.of(tactics); }
    public void setTactics(List<String> v) { this.tactics = v == null ? new String[0] : v.toArray(new String[0]); }

    public List<String> getPlatforms() { return List.of(platforms); }
    public void setPlatforms(List<String> v) { this.platforms = v == null ? new String[0] : v.toArray(new String[0]); }

    public List<String> getDataSources() { return List.of(dataSources); }
    public void setDataSources(List<String> v) { this.dataSources = v == null ? new String[0] : v.toArray(new String[0]); }

    public String getDetection() { return detection; }
    public void setDetection(String detection) { this.detection = detection; }

    public List<String> getPermissionsRequired() { return List.of(permissionsRequired); }
    public void setPermissionsRequired(List<String> v) { this.permissionsRequired = v == null ? new String[0] : v.toArray(new String[0]); }

    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}
