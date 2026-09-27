package com.martecyber.ares.assets.dto;

import com.martecyber.ares.assets.Asset;

import java.util.List;

/** Assets and relationships within a bounded hop radius of a focus asset. */
public record AssetNeighborhoodDto(List<Asset> assets, List<AssetRelationshipDto> relationships) {}
