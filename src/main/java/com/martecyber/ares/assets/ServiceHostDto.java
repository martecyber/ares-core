package com.martecyber.ares.assets;

/** Identifies the HOST that owns a given SERVICE asset (via INTERFACE). */
public record ServiceHostDto(Long hostId, String hostIdentifier, String hostCode) {}
