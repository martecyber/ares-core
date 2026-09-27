package com.martecyber.ares.kb.testing;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A rich-text how-to article describing how to run a test. {@code content} holds
 * HTML (images embedded inline as data URIs). Procedures link to testing-guide
 * points and external-database entries so auditors can reach relevant docs from a
 * project checklist.
 */
@Entity
@Table(name = "testing_procedure", schema = "ares")
public class TestingProcedure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String tags = "[]";

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }

    public String getTags() { return tags; }
    public void setTags(String v) { this.tags = v != null ? v : "[]"; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long v) { this.creatorId = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
