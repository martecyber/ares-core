package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "report_field_template", schema = "ares")
public class ReportFieldTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "field_type_id", nullable = false)
    private Long fieldTypeId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(columnDefinition = "text")
    private String content = "";
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId()                   { return id; }
    public Long getFieldTypeId()          { return fieldTypeId; }
    public void setFieldTypeId(Long v)    { this.fieldTypeId = v; }
    public String getName()               { return name; }
    public void setName(String v)         { this.name = v; }
    public String getContent()            { return content; }
    public void setContent(String v)      { this.content = v != null ? v : ""; }
    public boolean isDefault()            { return isDefault; }
    public void setDefault(boolean v)     { this.isDefault = v; }
    public OffsetDateTime getCreatedAt()  { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt()  { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
