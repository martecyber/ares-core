package com.martecyber.ares.research.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Merges the board in the URL path (source — deleted) into {@code targetBoardId} (survives),
 *  same source/target direction convention as {@code AssetService.merge}. */
public record MergeResearchBoardsRequest(
    @NotNull Long targetBoardId,
    @NotBlank String title
) {}
