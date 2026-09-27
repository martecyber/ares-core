package com.martecyber.ares.references;

import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.templates.FindingTemplate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "reference_entry", schema = "ares")
public class ReferenceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    /** Set only for URL-catalog entries — the linked website. Null for KB-backed references. */
    @Column(columnDefinition = "text")
    private String url;

    @Column(name = "favicon_bucket", length = 100)
    private String faviconBucket;

    @Column(name = "favicon_object_key", length = 300)
    private String faviconObjectKey;

    @Column(name = "favicon_content_type", length = 100)
    private String faviconContentType;

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        schema = "ares",
        name = "reference_entry_finding",
        joinColumns = @JoinColumn(name = "reference_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "finding_id")
    )
    private Set<Finding> findings = new HashSet<>();

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        schema = "ares",
        name = "reference_entry_detection",
        joinColumns = @JoinColumn(name = "reference_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "detection_id")
    )
    private Set<Detection> detections = new HashSet<>();

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        schema = "ares",
        name = "reference_entry_finding_template",
        joinColumns = @JoinColumn(name = "reference_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "finding_template_id")
    )
    private Set<FindingTemplate> findingTemplates = new HashSet<>();

    public Long getId() { return id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCatalogId() { return catalogId; }
    public void setCatalogId(Long catalogId) { this.catalogId = catalogId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    // Internal S3 location — never exposed over the API, only `faviconUrl` below is.
    @JsonIgnore public String getFaviconBucket() { return faviconBucket; }
    public void setFaviconBucket(String faviconBucket) { this.faviconBucket = faviconBucket; }

    @JsonIgnore public String getFaviconObjectKey() { return faviconObjectKey; }
    public void setFaviconObjectKey(String faviconObjectKey) { this.faviconObjectKey = faviconObjectKey; }

    @JsonIgnore public String getFaviconContentType() { return faviconContentType; }
    public void setFaviconContentType(String faviconContentType) { this.faviconContentType = faviconContentType; }

    /** Derived, not persisted — the servable path the frontend uses as an &lt;img&gt; source. */
    public String getFaviconUrl() {
        return faviconObjectKey != null ? "/api/v1/reference-catalogs/entries/" + id + "/favicon" : null;
    }

    public Set<Finding> getFindings() { return findings; }
    public void setFindings(Set<Finding> findings) { this.findings = findings; }

    public Set<Detection> getDetections() { return detections; }
    public void setDetections(Set<Detection> detections) { this.detections = detections; }

    public Set<FindingTemplate> getFindingTemplates() { return findingTemplates; }
    public void setFindingTemplates(Set<FindingTemplate> findingTemplates) { this.findingTemplates = findingTemplates; }
}
