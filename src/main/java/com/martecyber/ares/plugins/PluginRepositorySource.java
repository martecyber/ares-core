package com.martecyber.ares.plugins;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * A registered plugin marketplace repository — a base URL to a static, directory-based repo (see
 * {@link PluginBrowseService}'s own doc comment for the on-disk/on-bucket layout it expects at
 * that URL). One official row is seeded by migration V202; {@code official} rows can be disabled
 * but never deleted (enforced in {@link PluginRepositorySourceService}, not here).
 */
@Entity
@Table(name = "plugin_repository_source", schema = "ares")
public class PluginRepositorySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "base_url", nullable = false, columnDefinition = "TEXT")
    private String baseUrl;

    @Column(nullable = false)
    private boolean official;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public boolean isOfficial() { return official; }
    public void setOfficial(boolean official) { this.official = official; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }

    public OffsetDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(OffsetDateTime addedAt) { this.addedAt = addedAt; }
}
