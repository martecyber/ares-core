package com.martecyber.ares.assets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAssetRelationshipRequest(
    @NotNull Long toAssetId,
    @NotBlank String linkType
) {}
