package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "report_field", schema = "ares")
public class ReportField {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_id", nullable = false)
    private Long reportId;
    @Column(name = "field_type_id")
    private Long fieldTypeId;
    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;
    @Column(columnDefinition = "text")
    private String content;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId()                   { return id; }
    public Long getReportId()             { return reportId; }
    public void setReportId(Long v)       { this.reportId = v; }
    public Long getFieldTypeId()          { return fieldTypeId; }
    public void setFieldTypeId(Long v)    { this.fieldTypeId = v; }
    public String getFieldName()          { return fieldName; }
    public void setFieldName(String v)    { this.fieldName = v; }
    public String getContent()            { return content; }
    public void setContent(String v)      { this.content = v; }
    public OffsetDateTime getCreatedAt()  { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt()  { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
