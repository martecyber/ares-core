package com.martecyber.ares.reporting;

import java.io.Serializable;
import java.util.Objects;

public class ReportFindingId implements Serializable {
    private Long reportId;
    private Long findingId;
    public ReportFindingId() {}
    public ReportFindingId(Long reportId, Long findingId) { this.reportId = reportId; this.findingId = findingId; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ReportFindingId that)) return false;
        return Objects.equals(reportId, that.reportId) && Objects.equals(findingId, that.findingId);
    }
    @Override public int hashCode() { return Objects.hash(reportId, findingId); }
}
