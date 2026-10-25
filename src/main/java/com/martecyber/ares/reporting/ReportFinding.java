package com.martecyber.ares.reporting;

import jakarta.persistence.*;

@Entity
@Table(name = "report_finding", schema = "ares")
@IdClass(ReportFindingId.class)
public class ReportFinding {

    /** Reserved variable of the section every pre-sections report (and every unsectioned pick) lives in. */
    public static final String DEFAULT_SECTION = "default";

    @Id @Column(name = "report_id")        private Long reportId;
    @Id @Column(name = "finding_id")       private Long findingId;
    @Id @Column(name = "section_variable") private String sectionVariable = DEFAULT_SECTION;

    @Column(name = "section_label") private String sectionLabel;

    public ReportFinding() {}
    public ReportFinding(Long reportId, Long findingId) { this.reportId = reportId; this.findingId = findingId; }
    public ReportFinding(Long reportId, Long findingId, String sectionVariable, String sectionLabel) {
        this.reportId = reportId;
        this.findingId = findingId;
        this.sectionVariable = sectionVariable;
        this.sectionLabel = sectionLabel;
    }

    public Long getReportId()          { return reportId; }
    public Long getFindingId()         { return findingId; }
    public String getSectionVariable() { return sectionVariable; }
    public String getSectionLabel()    { return sectionLabel; }
    public boolean isDefaultSection()  { return DEFAULT_SECTION.equals(sectionVariable); }
}
