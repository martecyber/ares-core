package com.martecyber.ares.reporting;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "report_finding", schema = "ares")
@IdClass(ReportFindingId.class)
public class ReportFinding {

    @Id @Column(name = "report_id")  private Long reportId;
    @Id @Column(name = "finding_id") private Long findingId;

    public ReportFinding() {}
    public ReportFinding(Long reportId, Long findingId) { this.reportId = reportId; this.findingId = findingId; }

    public Long getReportId()  { return reportId; }
    public Long getFindingId() { return findingId; }
}
