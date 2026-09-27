package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "report_template", schema = "ares")
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column
    private String description;

    @Column(nullable = false, length = 20)
    private String format = "docx";

    @Column(name = "is_generic", nullable = false)
    private boolean isGeneric = false;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(nullable = false, length = 100)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    /** Hex RGB color used as placeholder in the template (default: FF00FF = magenta). */
    @Column(name = "magic_color", length = 6)
    private String magicColor = "FF00FF";

    /** Per-priority-level report styling: severity key → { bgColor, textColor, label } */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priority_colors", columnDefinition = "jsonb")
    private Map<String, PriorityColor> priorityColors;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public boolean isGeneric() { return isGeneric; }
    public void setGeneric(boolean generic) { isGeneric = generic; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getMagicColor() { return magicColor != null ? magicColor : "FF00FF"; }
    public void setMagicColor(String magicColor) { this.magicColor = magicColor; }
    public Map<String, PriorityColor> getPriorityColors() { return priorityColors; }
    public void setPriorityColors(Map<String, PriorityColor> priorityColors) { this.priorityColors = priorityColors; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
