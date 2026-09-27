package com.martecyber.ares.findings.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PublishBatchRequest(
    @NotEmpty List<Long> ids
) {}
