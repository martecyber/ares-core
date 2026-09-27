package com.martecyber.ares.organizations.dto;

import java.time.LocalDate;
import java.util.List;

public record OrgDashboardDto(
    Stats stats,
    List<ActiveProject> activeProjects,
    List<UrgentFinding> urgentFindings
) {
    public record Stats(
        long critical, long high, long medium, long low,
        long dueIn7Days, long slaPassed
    ) {}

    public record ActiveProject(
        Long id, String name, String code, String status, LocalDate startDate
    ) {}

    public record UrgentFinding(
        Long id, String code, String title, String severity,
        String statusName, Long projectId, LocalDate slaDeadline
    ) {}
}
