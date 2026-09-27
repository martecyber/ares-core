package com.martecyber.ares.projects.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ProjectDto(
    Long id,
    Long organizationId,
    String organizationName,
    String name,
    String code,
    Long typeId,
    String typeName,
    String typeCode,
    /** Code of the parent type; null for root types (e.g. "MONITOR" for EASM subtypes). */
    String supertypeCode,
    /** Computed: scheduled | active | past_due | completed */
    String status,
    LocalDate startDate,
    LocalDate endDate,
    OffsetDateTime completedAt,
    Long ownerUserId,
    List<ScopeEntryDto> scopeEntries,
    List<MemberDto> members,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /** Iteration cadence for MONITOR projects: weekly|biweekly|monthly|quarterly|semiannual|annual */
    String iterationCadence,
    /** When true, MONITOR iterations advance automatically with the calendar; when false
     *  (default), advancing to a new iteration requires manual approval. */
    boolean autoAdvanceIterations,
    /** When true, CLIENT_USER/CLIENT_ADMIN accounts can view this project's Detections
     *  read-only. Off by default. */
    boolean clientsCanViewDetections
) {
    public record MemberDto(Long userId, String userEmail, String userDisplayName, String role, OffsetDateTime addedAt) {}
}
