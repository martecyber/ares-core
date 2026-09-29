package com.martecyber.ares.affections.dto;

/** A distinct asset touched by one or more findings — see FindingService.affectedAssets. */
public record AffectedAssetDto(Long id, String type, String identifier) {}
