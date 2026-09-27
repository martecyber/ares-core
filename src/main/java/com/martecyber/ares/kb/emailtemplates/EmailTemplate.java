package com.martecyber.ares.kb.emailtemplates;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A reusable HTML email body for delivering a finding as a report by email — the email
 * counterpart to a DOCX {@code ReportTemplate} (see {@code FindingEmailReportService},
 * {@code ACTION_REPORT_FINDING}). {@code subjectTemplate}/{@code htmlContent} may contain
 * {@code {{var}}} placeholders, including {@code {{#name}}...{{/name}}} repeat blocks, resolved
 * against the finding's data at send time (see {@code MessagingTemplate.renderHtmlSafe} and
 * {@code FindingPresentationService}). {@code htmlContent} is sanitized server-side on every save
 * by {@link EmailTemplateService} — never trust the editor's client-side sanitization alone.
 */
@Entity
@Table(name = "email_template", schema = "ares")
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "subject_template", length = 300)
    private String subjectTemplate;

    @Column(name = "html_content", columnDefinition = "text")
    private String htmlContent;

    /** Per-severity {@code severityLabel}/{@code severityColor} override for this template, keyed
     *  by raw severity ({@code critical}/{@code high}/{@code medium}/{@code low}/{@code info}) —
     *  the email-template equivalent of {@code ReportTemplate.priorityColors}. A level with no
     *  entry (or a null map) falls back to the P0-P4 default label and palette — see
     *  {@code FindingPresentationService#severityDisplay}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priority_colors", columnDefinition = "jsonb")
    private Map<String, PriorityDisplayEntry> priorityColors;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getSubjectTemplate() { return subjectTemplate; }
    public void setSubjectTemplate(String v) { this.subjectTemplate = v; }

    public String getHtmlContent() { return htmlContent; }
    public void setHtmlContent(String v) { this.htmlContent = v; }

    public Map<String, PriorityDisplayEntry> getPriorityColors() { return priorityColors; }
    public void setPriorityColors(Map<String, PriorityDisplayEntry> v) { this.priorityColors = v; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long v) { this.creatorId = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
