package com.martecyber.ares.kb.kev;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * One (CVE, source) row of a Known Exploited Vulnerabilities catalog — {@code source} is
 * {@code "cisa"} or {@code "vulncheck"}. Replaces the two separate Mongo collections {@code
 * kb_cisa_kev}/{@code kb_vulncheck_kev} with one unified Postgres table (AQL-wide initiative,
 * Phase 4) — see V151's migration comment for why a single (cve_id, source) shape and no FK to
 * {@code ares.cve}. A surrogate {@code id} (rather than a composite {@code (cveId, source)} key,
 * the {@code @IdClass} pattern used elsewhere in this codebase e.g. {@code AffectionAsset}) is
 * required here specifically because this entity is a {@link
 * com.martecyber.ares.aql.registry.RelationAqlField} target — {@code
 * PostgresSpecificationCompiler#compileRelation} always selects {@code target.get("id")} for its
 * correlated-subquery, which needs a plain single-column identifier to resolve against.
 */
@Entity
@Table(name = "cve_kev_detail", schema = "ares")
public class CveKevDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cve_id", nullable = false)
    private String cveId;

    /** {@code "cisa"} or {@code "vulncheck"}. */
    @Column(nullable = false)
    private String source;

    @Column(name = "vendor_project")
    private String vendorProject;

    private String product;

    @Column(name = "vulnerability_name")
    private String vulnerabilityName;

    @Column(name = "short_description")
    private String shortDescription;

    @Column(name = "required_action")
    private String requiredAction;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "date_added")
    private LocalDate dateAdded;

    /** VulnCheck rows only — CISA's own date-added for the same CVE, when VulnCheck's feed
     *  reports it; always null for {@code source="cisa"} rows (CISA's own dateAdded is already
     *  {@link #dateAdded} for those). */
    @Column(name = "cisa_date_added")
    private LocalDate cisaDateAdded;

    @Column(name = "known_ransomware_campaign_use", nullable = false)
    private boolean knownRansomwareCampaignUse;

    /** VulnCheck-only signal — always null for {@code source="cisa"} rows (CISA's feed has no
     *  such concept), hence {@code Boolean} not {@code boolean}. */
    @Column(name = "reported_exploited_by_canaries")
    private Boolean reportedExploitedByCanaries;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] cwes = new String[0];

    /** VulnCheck-only — empty for {@code source="cisa"} rows. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "xdb_urls", columnDefinition = "text[]")
    private String[] xdbUrls = new String[0];

    /** VulnCheck-only — empty for {@code source="cisa"} rows. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reported_exploitation_urls", columnDefinition = "text[]")
    private String[] reportedExploitationUrls = new String[0];

    /** CISA-only free-text field — always null for {@code source="vulncheck"} rows. */
    private String notes;

    @Column(name = "synced_at")
    private Instant syncedAt;

    public Long getId() { return id; }

    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getVendorProject() { return vendorProject; }
    public void setVendorProject(String v) { this.vendorProject = v; }

    public String getProduct() { return product; }
    public void setProduct(String v) { this.product = v; }

    public String getVulnerabilityName() { return vulnerabilityName; }
    public void setVulnerabilityName(String v) { this.vulnerabilityName = v; }

    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String v) { this.shortDescription = v; }

    public String getRequiredAction() { return requiredAction; }
    public void setRequiredAction(String v) { this.requiredAction = v; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }

    public LocalDate getDateAdded() { return dateAdded; }
    public void setDateAdded(LocalDate v) { this.dateAdded = v; }

    public LocalDate getCisaDateAdded() { return cisaDateAdded; }
    public void setCisaDateAdded(LocalDate v) { this.cisaDateAdded = v; }

    public boolean isKnownRansomwareCampaignUse() { return knownRansomwareCampaignUse; }
    public void setKnownRansomwareCampaignUse(boolean v) { this.knownRansomwareCampaignUse = v; }

    public Boolean getReportedExploitedByCanaries() { return reportedExploitedByCanaries; }
    public void setReportedExploitedByCanaries(Boolean v) { this.reportedExploitedByCanaries = v; }

    public List<String> getCwes() { return List.of(cwes); }
    public void setCwes(List<String> v) { this.cwes = normalize(v); }

    public List<String> getXdbUrls() { return List.of(xdbUrls); }
    public void setXdbUrls(List<String> v) { this.xdbUrls = v == null ? new String[0] : v.toArray(new String[0]); }

    public List<String> getReportedExploitationUrls() { return List.of(reportedExploitationUrls); }
    public void setReportedExploitationUrls(List<String> v) { this.reportedExploitationUrls = v == null ? new String[0] : v.toArray(new String[0]); }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant v) { this.syncedAt = v; }

    /** cwes is HAS-queryable (ArrayAqlField) — lowercase-normalized at write time to match every
     *  other case-insensitive string comparison in AQL, same contract as CveEntry.setCwes. xdbUrls/
     *  reportedExploitationUrls are plain URLs, not HAS-queryable, so they're stored as-is. */
    private static String[] normalize(List<String> v) {
        return v == null ? new String[0] : v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }
}
