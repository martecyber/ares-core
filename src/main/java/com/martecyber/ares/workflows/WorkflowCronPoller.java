package com.martecyber.ares.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.PlatformSettingsService;
import com.martecyber.ares.integrations.schedule.IntegrationScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/** Fires {@code trigger_type='cron'} workflow triggers whose {@code next_run_at} is due — same
 *  every-minute polling shape as {@link IntegrationScheduleService#runDue()}, whose 5-to-6-field
 *  cron conversion ({@link IntegrationScheduleService#toSpringCron}) this reuses directly rather
 *  than duplicating. */
@Component
public class WorkflowCronPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCronPoller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowTriggerRepository triggerRepo;
    private final WorkflowRunService runService;
    private final PlatformSettingsService platformSettings;

    public WorkflowCronPoller(WorkflowTriggerRepository triggerRepo, WorkflowRunService runService,
                               PlatformSettingsService platformSettings) {
        this.triggerRepo = triggerRepo;
        this.runService = runService;
        this.platformSettings = platformSettings;
    }

    @Scheduled(cron = "0 * * * * *")
    public void runDue() {
        OffsetDateTime now = OffsetDateTime.now();
        for (WorkflowTrigger trigger : triggerRepo.findDueCronTriggers(now)) {
            String cronExpression = readCronExpression(trigger.getConfig());
            try {
                runService.start(trigger.getWorkflowId(), trigger.getNodeId(), Map.of(), "cron", null);
            } catch (Exception e) {
                log.error("Failed to run cron-triggered workflow {} (trigger {})",
                    trigger.getWorkflowId(), trigger.getId(), e);
            } finally {
                // Advance next_run_at even on failure — an unfixed cron expression shouldn't
                // fire every minute forever, same rationale as IntegrationScheduleService.runDue().
                trigger.setNextRunAt(cronExpression == null ? null : computeNext(cronExpression, platformZone()));
                triggerRepo.save(trigger);
            }
        }
    }

    private ZoneId platformZone() {
        return ZoneId.of(platformSettings.getTimezone());
    }

    /** {@code fiveFieldCron}'s wall-clock fields ("15 9 * * *" → 9:15) are meant in the
     *  platform-configured timezone (Admin → Configuration → {@code PlatformSettingsService}
     *  .getTimezone()), not the JVM's own default zone — deployed containers run UTC with
     *  nothing overriding {@code user.timezone}, so resolving against
     *  {@code ZoneId.systemDefault()} silently fired a "9:15 Madrid" trigger at 9:15 UTC (11:15
     *  Madrid in summer) instead. Mirrors {@code AgentTaskScheduleService.computeNextRun}'s
     *  already-correct instant→zone→local→zone round trip exactly. */
    public static OffsetDateTime computeNext(String fiveFieldCron, ZoneId zone) {
        try {
            CronExpression expr = CronExpression.parse(IntegrationScheduleService.toSpringCron(fiveFieldCron));
            LocalDateTime nowLocal = OffsetDateTime.now().atZoneSameInstant(zone).toLocalDateTime();
            LocalDateTime next = expr.next(nowLocal);
            return next != null ? next.atZone(zone).toOffsetDateTime() : null;
        } catch (Exception e) {
            log.warn("Invalid workflow cron expression '{}': {}", fiveFieldCron, e.getMessage());
            return null;
        }
    }

    private String readCronExpression(String configJson) {
        try {
            JsonNode node = MAPPER.readTree(configJson).get("cronExpression");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
