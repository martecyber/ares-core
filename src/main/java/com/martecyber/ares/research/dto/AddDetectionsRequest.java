package com.martecyber.ares.research.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AddDetectionsRequest(@NotEmpty List<Long> detectionIds) {}
