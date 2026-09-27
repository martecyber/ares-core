package com.martecyber.ares.kb.owasp;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Postgres-backed OWASP Top 10 record (AQL-wide initiative, Phase 5 — migrated off MongoDB's
 * {@code kb_owasp}). Simplest of the four Phase 5 catalogs: flat scalars plus two native {@code
 * text[]} arrays, no nested records/JSONB needed. Natural key is {@code (owaspId, year)} — the
 * same rank id (e.g. "A01") recurs across editions with different content each time.
 */
@Entity
@Table(name = "owasp", schema = "ares")
public class OwaspEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "A01" */
    @Column(name = "owasp_id", nullable = false)
    private String owaspId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int rank;

    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] cwes = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] preventions = new String[0];

    @Column(name = "synced_at")
    private Instant syncedAt;

    public Long getId() { return id; }

    public String getOwaspId() { return owaspId; }
    public void setOwaspId(String owaspId) { this.owaspId = owaspId; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /** cwes is HAS-queryable (ArrayAqlField) — lowercase-normalized at write time to match every
     *  other case-insensitive string comparison in AQL, same contract as CveEntry.setCwes. */
    public List<String> getCwes() { return List.of(cwes); }
    public void setCwes(List<String> cwes) {
        this.cwes = cwes == null ? new String[0] : cwes.stream().map(s -> s.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }

    public List<String> getPreventions() { return List.of(preventions); }
    public void setPreventions(List<String> preventions) {
        this.preventions = preventions == null ? new String[0] : preventions.toArray(new String[0]);
    }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }
}
