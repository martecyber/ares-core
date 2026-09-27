package com.martecyber.ares.projects.dto;

import com.martecyber.ares.findings.dto.FindingDto;

import java.time.OffsetDateTime;

public record ProjectRetestFindingDto(
    Long linkId,
    OffsetDateTime linkedAt,
    String linkedByName,
    Long originProjectId,
    String originProjectName,
    String originProjectCode,
    FindingDto finding
) {}
