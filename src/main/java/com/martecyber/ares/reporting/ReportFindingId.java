package com.martecyber.ares.reporting;

import java.io.Serializable;
import java.util.Objects;

public class ReportFindingId implements Serializable {
    private Long reportId;
    private Long findingId;
    private String sectionVariable;
    public ReportFindingId() {}
    public ReportFindingId(Long reportId, Long findingId, String sectionVariable) {
        this.reportId = reportId; this.findingId = findingId; this.sectionVariable = sectionVariable;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ReportFindingId that)) return false;
        return Objects.equals(reportId, that.reportId) && Objects.equals(findingId, that.findingId)
            && Objects.equals(sectionVariable, that.sectionVariable);
    }
    @Override public int hashCode() { return Objects.hash(reportId, findingId, sectionVariable); }
}
