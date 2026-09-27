package com.martecyber.ares.workflows;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for a real, user-reported bug: a "15 9 * * *" cron trigger fired at 11:15
 * instead of 9:15 for a user with Europe/Madrid configured, because {@code computeNext} resolved
 * the cron's wall-clock fields against {@code ZoneId.systemDefault()} (the JVM's own zone —
 * effectively UTC in deployed containers) instead of the platform-configured timezone. These
 * tests deliberately don't depend on what "now" happens to be when the suite runs — each
 * assertion re-derives the expected local time in the SAME zone it was computed for, which is
 * DST- and JVM-zone-independent.
 */
class WorkflowCronPollerTest {

    @Test
    void computeNextFiresAtTheStatedWallClockTimeInWhicheverZoneIsGiven() {
        for (String tz : List.of("UTC", "Europe/Madrid", "America/New_York", "Asia/Tokyo")) {
            ZoneId zone = ZoneId.of(tz);
            OffsetDateTime next = WorkflowCronPoller.computeNext("15 9 * * *", zone);
            assertNotNull(next, "expected a next-fire time for zone " + tz);
            LocalTime localTime = next.atZoneSameInstant(zone).toLocalTime();
            assertEquals(LocalTime.of(9, 15), localTime, "cron '15 9 * * *' should mean 09:15 in " + tz);
        }
    }

    @Test
    void theSameCronInDifferentZonesProducesDifferentRealInstants() {
        // Before the fix, every zone argument was ignored (ZoneId.systemDefault() always won),
        // so this would have failed — both calls would resolve to the exact same instant.
        OffsetDateTime utc = WorkflowCronPoller.computeNext("0 12 * * *", ZoneId.of("UTC"));
        OffsetDateTime madrid = WorkflowCronPoller.computeNext("0 12 * * *", ZoneId.of("Europe/Madrid"));
        assertNotEquals(utc.toInstant(), madrid.toInstant());
    }

    @Test
    void invalidCronReturnsNullRatherThanThrowing() {
        assertNull(WorkflowCronPoller.computeNext("not a cron", ZoneId.of("UTC")));
    }
}
