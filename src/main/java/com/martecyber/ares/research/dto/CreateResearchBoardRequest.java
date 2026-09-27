package com.martecyber.ares.research.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** detectionIds is optional — a board can be created empty (title only) and have detections
 *  added afterward through the same "Add detections" action the board dialog otherwise uses. */
public record CreateResearchBoardRequest(
    @NotBlank String title,
    List<Long> detectionIds
) {}
