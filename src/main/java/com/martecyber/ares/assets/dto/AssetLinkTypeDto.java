package com.martecyber.ares.assets.dto;

import java.util.List;

/** One row of the AssetLinkType matrix; {@code manual=false} means the platform computes it itself. */
public record AssetLinkTypeDto(String linkType, String fromType, List<String> toTypes, boolean manual) {}
