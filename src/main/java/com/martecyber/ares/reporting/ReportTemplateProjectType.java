package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "report_template_project_type", schema = "ares")
public class ReportTemplateProjectType {

    @EmbeddedId
    private Id id = new Id();

    public ReportTemplateProjectType() {}
    public ReportTemplateProjectType(Long templateId, Long projectTypeId) {
        this.id = new Id(templateId, projectTypeId);
    }

    public Id getId() { return id; }
    public void setId(Id id) { this.id = id; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "template_id") private Long templateId;
        @Column(name = "project_type_id") private Long projectTypeId;

        public Id() {}
        public Id(Long templateId, Long projectTypeId) {
            this.templateId = templateId;
            this.projectTypeId = projectTypeId;
        }

        public Long getTemplateId() { return templateId; }
        public Long getProjectTypeId() { return projectTypeId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(templateId, id.templateId) && Objects.equals(projectTypeId, id.projectTypeId);
        }
        @Override public int hashCode() { return Objects.hash(templateId, projectTypeId); }
    }
}
