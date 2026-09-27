package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "report", schema = "ares")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 20)
    private String format = "pdf";

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "report_bucket", length = 100)
    private String reportBucket;

    @Column(name = "report_object_key", length = 500)
    private String reportObjectKey;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "generated_by")
    private Long generatedBy;

    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getReportBucket() { return reportBucket; }
    public void setReportBucket(String reportBucket) { this.reportBucket = reportBucket; }
    public String getReportObjectKey() { return reportObjectKey; }
    public void setReportObjectKey(String reportObjectKey) { this.reportObjectKey = reportObjectKey; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(Long generatedBy) { this.generatedBy = generatedBy; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
}
