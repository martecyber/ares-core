package com.martecyber.ares.kb.cve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Postgres-backed CVE record (AQL-wide initiative, Phase 3 — migrated off MongoDB's {@code
 * kb_cve}). {@code cwes} is a native {@code text[]} column (HAS-queryable, GIN-indexed).
 * {@code affectedProducts}/{@code references}/{@code cvssScores}/{@code ssvc} stay JSONB —
 * display-oriented structured data with no AQL query requirement — but are exposed through
 * ordinary typed getters/setters (parsing/serializing the underlying JSON string field on the
 * fly) so this entity's own public shape, and therefore its default Jackson REST serialization,
 * is unchanged from the pre-migration Mongo document: callers (CveService, CveController, the
 * frontend KB browser) never see the JSON-string storage detail. This mirrors {@code
 * Finding.fields}' own established jsonb-string-plus-typed-accessor convention in this codebase,
 * just inlined onto the entity itself rather than a separate {@code FindingFields}-style helper,
 * since each of these four fields needs its own independent (de)serialization rather than one
 * shared flat map.
 */
@Entity
@Table(name = "cve", schema = "ares")
public class CveEntry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cve_id", nullable = false, unique = true)
    private String cveId;

    private String state;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "cvss_score")
    private Double cvssScore;

    @Column(name = "cvss_vector")
    private String cvssVector;

    @Column(name = "cvss_version")
    private String cvssVersion;

    private String severity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] cwes = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_products", nullable = false, columnDefinition = "jsonb")
    private String affectedProductsJson = "[]";

    // Column named "refs" rather than "references" — the latter is a reserved SQL keyword that
    // would need dialect-specific quoting for no real benefit, since the Java-side property name
    // (and therefore the REST JSON field name) is "references" either way.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refs", nullable = false, columnDefinition = "jsonb")
    private String referencesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cvss_scores", nullable = false, columnDefinition = "jsonb")
    private String cvssScoresJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ssvc", columnDefinition = "jsonb")
    private String ssvcJson;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    @Column(name = "synced_at")
    private Instant syncedAt;

    /** Denormalized from the CISA KEV catalog on every KEV sync (see CisaKevService), so the CVE
     *  list can be sorted/filtered by KEV status without a cross-table join. */
    @Column(name = "kev_listed", nullable = false)
    private boolean kevListed;

    @Column(name = "kev_date_added")
    private LocalDate kevDateAdded;

    /** Same denormalization, from VulnCheck's KEV catalog (see VulnCheckKevService). */
    @Column(name = "vulncheck_kev_listed", nullable = false)
    private boolean vulncheckKevListed;

    @Column(name = "vulncheck_kev_date_added")
    private LocalDate vulncheckKevDateAdded;

    /** {@code kevListed || vulncheckKevListed} — kept in sync so the CVE list can sort on
     *  "listed in any KEV source" with a single column, without OR-ing two properties. */
    @Column(name = "any_kev_listed", nullable = false)
    private boolean anyKevListed;

    /** Denormalized from the KB Exploits catalog (see ExploitService) — number of exploit/PoC
     *  entries mapped to this CVE. A count rather than a flag so the list can sort by it directly. */
    @Column(name = "exploit_count", nullable = false)
    private int exploitCount;

    public record AffectedProduct(String vendor, String product, String defaultStatus, List<VersionRange> versions) {}

    /** One entry from a CVE affected[].versions[] array — a version range with its own
     *  affected/unaffected status, mirroring the CVE 5.x schema directly instead of flattening it
     *  to a bare version string (which threw away exactly the information needed to tell
     *  "affected at X before Y" apart from "unaffected from X through Y"). */
    public record VersionRange(
        String version,
        String status,
        String lessThan,
        String lessThanOrEqual,
        String versionType
    ) {}

    public record Reference(String url, String name, List<String> tags) {}

    /**
     * One CVSS score from a specific provider.
     * isDefault marks the authoritative score (NVD ADP preferred, then other ADP, then CNA).
     * The top-level cvssScore/cvssVector/cvssVersion/severity fields mirror the default score
     * for efficient querying.
     */
    public record CvssScore(
        String source,
        String sourceRole,
        String version,
        Double score,
        String vector,
        String severity,
        boolean isDefault
    ) {}

    /** Raw decision-point values from CISA's published SSVC assessment — codes match the option
     *  codes of the seeded CISAv1 methodology's "cisa" role tree exactly (e.g. exploitation:
     *  none/poc/active), so the frontend can resolve them to the same labels/help text used when
     *  scoring findings, without a separate lookup table. Display-only, no computed outcome: CISA
     *  never publishes one, since the tree's 4th decision point (Mission and Well-Being Impact) is
     *  organization-specific and can't be determined on the CVE's behalf. CVSS remains the source
     *  of truth for severity; this is purely supplementary context. */
    public record SsvcAssessment(
        String role,
        String exploitation,
        String automatable,
        String technicalImpact,
        String version,
        Instant timestamp
    ) {}

    // ── Getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getCvssScore() { return cvssScore; }
    public void setCvssScore(Double cvssScore) { this.cvssScore = cvssScore; }

    public String getCvssVector() { return cvssVector; }
    public void setCvssVector(String cvssVector) { this.cvssVector = cvssVector; }

    public String getCvssVersion() { return cvssVersion; }
    public void setCvssVersion(String cvssVersion) { this.cvssVersion = cvssVersion; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public List<String> getCwes() { return List.of(cwes); }
    public void setCwes(List<String> cwes) { this.cwes = cwes == null ? new String[0] : cwes.stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toArray(String[]::new); }

    public List<AffectedProduct> getAffectedProducts() { return parseList(affectedProductsJson, AffectedProduct[].class); }
    public void setAffectedProducts(List<AffectedProduct> v) { this.affectedProductsJson = toJson(v); }

    public List<Reference> getReferences() { return parseList(referencesJson, Reference[].class); }
    public void setReferences(List<Reference> v) { this.referencesJson = toJson(v); }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getLastModifiedAt() { return lastModifiedAt; }
    public void setLastModifiedAt(Instant lastModifiedAt) { this.lastModifiedAt = lastModifiedAt; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    public List<CvssScore> getCvssScores() { return parseList(cvssScoresJson, CvssScore[].class); }
    public void setCvssScores(List<CvssScore> v) { this.cvssScoresJson = toJson(v); }

    public boolean isKevListed() { return kevListed; }
    public void setKevListed(boolean kevListed) { this.kevListed = kevListed; }

    public LocalDate getKevDateAdded() { return kevDateAdded; }
    public void setKevDateAdded(LocalDate kevDateAdded) { this.kevDateAdded = kevDateAdded; }

    public boolean isVulncheckKevListed() { return vulncheckKevListed; }
    public void setVulncheckKevListed(boolean vulncheckKevListed) { this.vulncheckKevListed = vulncheckKevListed; }

    public LocalDate getVulncheckKevDateAdded() { return vulncheckKevDateAdded; }
    public void setVulncheckKevDateAdded(LocalDate vulncheckKevDateAdded) { this.vulncheckKevDateAdded = vulncheckKevDateAdded; }

    public boolean isAnyKevListed() { return anyKevListed; }
    public void setAnyKevListed(boolean anyKevListed) { this.anyKevListed = anyKevListed; }

    public int getExploitCount() { return exploitCount; }
    public void setExploitCount(int exploitCount) { this.exploitCount = exploitCount; }

    public SsvcAssessment getSsvc() {
        if (ssvcJson == null) return null;
        try { return MAPPER.readValue(ssvcJson, SsvcAssessment.class); }
        catch (Exception e) { return null; }
    }
    public void setSsvc(SsvcAssessment v) { this.ssvcJson = v == null ? null : toJson(v); }

    private static <T> List<T> parseList(String json, Class<T[]> arrayType) {
        if (json == null || json.isBlank()) return List.of();
        try { return List.of(MAPPER.readValue(json, arrayType)); }
        catch (Exception e) { return List.of(); }
    }

    private static String toJson(Object v) {
        try { return MAPPER.writeValueAsString(v == null ? List.of() : v); }
        catch (Exception e) { return "[]"; }
    }
}
