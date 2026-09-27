package com.martecyber.ares.organizations.dto;

import java.time.OffsetDateTime;

public record OrganizationDto(
    Long id,
    String name,
    String slug,
    String status,
    String settings,
    boolean hasLogo,
    SlaSettings sla,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    /** Resolution time limits (days) per severity. 0 = no limit. */
    public record SlaSettings(int critical, int high, int medium, int low, int info) {
        public static SlaSettings defaults() {
            return new SlaSettings(7, 30, 90, 180, 0);
        }
        public int forSeverity(String severity) {
            return switch (severity == null ? "" : severity.toLowerCase()) {
                case "critical" -> critical();
                case "high"     -> high();
                case "medium"   -> medium();
                case "low"      -> low();
                default         -> info();
            };
        }
    }
}
