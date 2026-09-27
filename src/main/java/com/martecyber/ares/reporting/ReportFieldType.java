package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "report_field_type", schema = "ares")
public class ReportFieldType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String name;
    @Column(nullable = false, length = 200)
    private String label;
    @Column(columnDefinition = "text")
    private String description;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
    @Column(name = "is_required", nullable = false)
    private boolean required = false;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId()            { return id; }
    public String getName()        { return name; }
    public void setName(String v)  { this.name = v; }
    public String getLabel()       { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getDescription()        { return description; }
    public void setDescription(String v)  { this.description = v; }
    public int getSortOrder()             { return sortOrder; }
    public void setSortOrder(int v)       { this.sortOrder = v; }
    public boolean isRequired()           { return required; }
    public void setRequired(boolean v)    { this.required = v; }
    public OffsetDateTime getCreatedAt()  { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt()  { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
