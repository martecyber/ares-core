package com.martecyber.ares.editorimages;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "editor_image", schema = "ares")
public class EditorImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false, length = 100)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Unguessable public identifier — never the sequential {@code id}. See V206's own comment
     *  for why. */
    @Column(nullable = false, unique = true)
    private java.util.UUID token;

    /** Scope for the GET endpoint's access check — both null means platform-wide (gated to MSSP
     *  staff instead of a specific org/project). Best-effort: captured from whatever context the
     *  uploading form has at upload time, since the editor is used from platform-wide forms too. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "project_id")
    private Long projectId;

    public Long getId() { return id; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public java.util.UUID getToken() { return token; }
    public void setToken(java.util.UUID token) { this.token = token; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
}
