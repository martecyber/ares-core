package com.martecyber.ares.findings.dto;

import java.time.LocalDate;

public record UpdateFindingRequest(
    String title,
    String severity,
    Long statusId,
    LocalDate dueDate,
    Boolean clearDueDate   // true → set dueDate to null (revert to SLA-computed)
) {}
