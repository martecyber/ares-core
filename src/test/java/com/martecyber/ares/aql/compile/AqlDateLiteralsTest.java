package com.martecyber.ares.aql.compile;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests for the shared "now"/relative-date resolver — no Spring context needed. */
class AqlDateLiteralsTest {

    private static void assertNear(OffsetDateTime expected, OffsetDateTime actual) {
        assertTrue(Duration.between(expected, actual).abs().getSeconds() < 5,
            "expected " + actual + " to be within 5s of " + expected);
    }

    @Test
    void bareNowResolvesToCurrentInstant() {
        assertNear(OffsetDateTime.now(), AqlDateLiterals.resolve("now"));
        assertNear(OffsetDateTime.now(), AqlDateLiterals.resolve("NOW"));
    }

    @Test
    void minusDaysMatchesThePreExistingBehavior() {
        assertNear(OffsetDateTime.now().minusDays(7), AqlDateLiterals.resolve("now-7d"));
        assertNear(OffsetDateTime.now().minusDays(7), AqlDateLiterals.resolve("NOW-7D"));
    }

    @Test
    void plusIsNowSupported() {
        assertNear(OffsetDateTime.now().plusDays(3), AqlDateLiterals.resolve("now+3d"));
    }

    @Test
    void hoursMinutesSecondsWeeksMonthsYears() {
        assertNear(OffsetDateTime.now().minusHours(6), AqlDateLiterals.resolve("now-6h"));
        assertNear(OffsetDateTime.now().minusMinutes(45), AqlDateLiterals.resolve("now-45m"));
        assertNear(OffsetDateTime.now().plusSeconds(30), AqlDateLiterals.resolve("now+30s"));
        assertNear(OffsetDateTime.now().minusWeeks(2), AqlDateLiterals.resolve("now-2w"));
        assertNear(OffsetDateTime.now().minusMonths(1), AqlDateLiterals.resolve("now-1M"));
        assertNear(OffsetDateTime.now().plusYears(1), AqlDateLiterals.resolve("now+1y"));
    }

    @Test
    void minutesAndMonthsAreCaseSensitiveOnPurpose() {
        OffsetDateTime minuteResult = AqlDateLiterals.resolve("now-1m");
        OffsetDateTime monthResult = AqlDateLiterals.resolve("now-1M");
        assertTrue(ChronoUnit.SECONDS.between(monthResult, minuteResult) > 60,
            "'m' (minutes) and 'M' (months) must resolve to meaningfully different instants");
    }

    @Test
    void isoDateAndDatetimeStillWork() {
        assertEquals(OffsetDateTime.parse("2026-01-01T10:15:30+01:00"),
            AqlDateLiterals.resolve("2026-01-01T10:15:30+01:00"));
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), AqlDateLiterals.resolve("2026-01-01"));
    }

    @Test
    void unknownUnitIsRejectedWithAHelpfulMessage() {
        var ex = assertThrows(AqlCompileException.class, () -> AqlDateLiterals.resolve("now-3x"));
        assertTrue(ex.getMessage().contains("Unknown relative date unit"));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(AqlCompileException.class, () -> AqlDateLiterals.resolve("not-a-date"));
    }
}
