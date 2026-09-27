package com.martecyber.ares.kb.schedule;

import java.time.OffsetDateTime;

/** Same shape the frontend (`KbScheduleDialog.vue`) already expected from the retired {@code
 *  KbSyncSchedule} JPA entity — deliberately preserved so the dialog needed no changes when its
 *  backing store switched to a locked {@code Workflow} (Workflows Phase H+). {@code id} is the
 *  underlying {@code Workflow.id}. */
public record KbSyncScheduleDto(
    Long id, String syncType, String cronExpression, boolean enabled,
    OffsetDateTime lastRunAt, OffsetDateTime nextRunAt, OffsetDateTime createdAt) {
}
