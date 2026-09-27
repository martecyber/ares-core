package com.martecyber.ares.projects.dto;

import java.time.LocalDate;

public record UpdateProjectRequest(
    String name,
    Long typeId,
    LocalDate startDate,
    LocalDate endDate,
    Long ownerUserId,
    String iterationCadence,
    Boolean autoAdvanceIterations,
    Boolean clientsCanViewDetections
) {}
