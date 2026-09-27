package com.martecyber.ares.imports;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "scan_import", schema = "ares")
public class ScanImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String tool;

    @Column(nullable = false, length = 30)
    private String format;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String filename;

    @Column(name = "assets_created", nullable = false)
    private int assetsCreated;

    @Column(name = "detections_created", nullable = false)
    private int detectionsCreated;

    @Column(name = "detections_updated", nullable = false)
    private int detectionsUpdated;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "nac_profile", length = 64)
    private String nacProfile;

    @Column(name = "visibility_recorded", nullable = false)
    private int visibilityRecorded;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public Long getId() { return id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public int getAssetsCreated() { return assetsCreated; }
    public void setAssetsCreated(int assetsCreated) { this.assetsCreated = assetsCreated; }

    public int getDetectionsCreated() { return detectionsCreated; }
    public void setDetectionsCreated(int detectionsCreated) { this.detectionsCreated = detectionsCreated; }

    public int getDetectionsUpdated() { return detectionsUpdated; }
    public void setDetectionsUpdated(int detectionsUpdated) { this.detectionsUpdated = detectionsUpdated; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getNacProfile() { return nacProfile; }
    public void setNacProfile(String nacProfile) { this.nacProfile = nacProfile; }

    public int getVisibilityRecorded() { return visibilityRecorded; }
    public void setVisibilityRecorded(int visibilityRecorded) { this.visibilityRecorded = visibilityRecorded; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
