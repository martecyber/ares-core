package com.martecyber.ares.reporting.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SendEmailReportRequest(
    @NotNull Long emailTemplateId,
    @NotNull Long integrationId,
    List<String> to,
    List<String> cc,
    List<String> bcc
) {}
