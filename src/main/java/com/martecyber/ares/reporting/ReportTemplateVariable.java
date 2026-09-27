package com.martecyber.ares.reporting;

import jakarta.persistence.*;

@Entity
@Table(name = "report_template_variable", schema = "ares")
public class ReportTemplateVariable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "variable_name", nullable = false, length = 100)
    private String variableName;

    /** 'system' or 'field_type' */
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    /** e.g. "finding.title", "finding.severity", "project.name" (when sourceType=system) */
    @Column(name = "system_field", length = 100)
    private String systemField;

    @Column(name = "field_type_id")
    private Long fieldTypeId;

    public Long getId() { return id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSystemField() { return systemField; }
    public void setSystemField(String systemField) { this.systemField = systemField; }
    public Long getFieldTypeId() { return fieldTypeId; }
    public void setFieldTypeId(Long fieldTypeId) { this.fieldTypeId = fieldTypeId; }
}
