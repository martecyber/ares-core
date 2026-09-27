package com.martecyber.ares.assets.dto;

/**
 * Enriched relationship DTO that includes the related asset's
 * code, identifier and type instead of just its primary key.
 */
public record AssetRelationshipDetailDto(
    Long   relatedAssetId,
    String relatedCode,
    String relatedIdentifier,
    String relatedType,
    String linkType,
    String direction   // "outgoing" (this → other) | "incoming" (other → this)
) {}
