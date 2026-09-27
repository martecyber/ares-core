package com.martecyber.ares.projects;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * Computes the iteration label for MONITOR projects given their configured cadence
 * and the date on which a finding is published.
 *
 * Label formats (YY = 2-digit year):
 *   weekly      → YY-Www  (ISO week, e.g. "26-W02")
 *   biweekly    → YY-BWnn (fortnight number 01-26, e.g. "26-BW03")
 *   monthly     → YY-MM   (e.g. "30-12")
 *   quarterly   → YY-Qq   (e.g. "27-Q2")
 *   semiannual  → YY-Ss   (e.g. "28-S1")
 *   annual      → YY      (e.g. "25")
 *   null / unknown → null  (no label; finding gets standard code)
 */
public final class MonitorIterationHelper {

    private MonitorIterationHelper() {}

    public static String computeLabel(String cadence, LocalDate date) {
        if (cadence == null || date == null) return null;
        int yy = date.getYear() % 100;
        return switch (cadence) {
            case "weekly" -> {
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                yield String.format("%02d-W%02d", yy, week);
            }
            case "biweekly" -> {
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                int fortnight = (week + 1) / 2;
                yield String.format("%02d-BW%02d", yy, fortnight);
            }
            case "monthly" -> String.format("%02d-%02d", yy, date.getMonthValue());
            case "quarterly" -> {
                int q = (date.getMonthValue() - 1) / 3 + 1;
                yield String.format("%02d-Q%d", yy, q);
            }
            case "semiannual" -> {
                int s = date.getMonthValue() <= 6 ? 1 : 2;
                yield String.format("%02d-S%d", yy, s);
            }
            case "annual" -> String.format("%02d", yy);
            default -> null;
        };
    }

    /** Returns true if the given project type code is MONITOR or a subtype of MONITOR. */
    public static boolean isMonitorCode(String typeCode) {
        return "MONITOR".equals(typeCode);
    }

    /**
     * Resolves the iteration label to actually use right now for the given project —
     * the officially "active" one, not necessarily what the calendar currently says.
     *
     * Bootstraps (adopts the calendar label) the first time a project is ever resolved,
     * since there's no prior iteration to preserve. After that, only auto-advance
     * projects keep following the calendar automatically; manual-approval projects keep
     * their persisted active label until {@code approveIteration} is called explicitly.
     */
    public static String resolveActiveLabel(Project project, ProjectRepository projectRepo) {
        String cadence = project.getIterationCadence();
        if (cadence == null) return null;
        String pending = computeLabel(cadence, LocalDate.now());
        String active = project.getActiveIterationLabel();
        if (active == null || (project.isAutoAdvanceIterations() && !pending.equals(active))) {
            project.setActiveIterationLabel(pending);
            projectRepo.save(project);
            return pending;
        }
        return active;
    }

    /** Start (inclusive) and end (EXCLUSIVE — the next iteration's start) of the cadence-defined
     *  period containing {@code referenceDate}. Companion to {@link #computeLabel} — that produces
     *  a display label for a date, this produces the actual date boundaries of the same period,
     *  needed for AQL's {@code current_iteration_start}/{@code current_iteration_end} context
     *  variables (AQL context-variables initiative) and reusable later for "last N iterations"
     *  chart bucketing. Biweekly mirrors {@code computeLabel}'s own fortnight grouping exactly
     *  (weeks 1-2 -&gt; fortnight 1, 3-4 -&gt; fortnight 2, ...): an odd ISO week number is always the
     *  first week of its fortnight, an even one the second, so walking back one week from an even
     *  week's Monday always lands on the fortnight's real start. */
    public static LocalDate[] iterationBounds(String cadence, LocalDate referenceDate) {
        if (cadence == null || referenceDate == null) return null;
        return switch (cadence) {
            case "weekly" -> {
                LocalDate start = referenceDate.with(DayOfWeek.MONDAY);
                yield new LocalDate[]{start, start.plusWeeks(1)};
            }
            case "biweekly" -> {
                LocalDate weekStart = referenceDate.with(DayOfWeek.MONDAY);
                int week = weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                LocalDate start = (week % 2 == 1) ? weekStart : weekStart.minusWeeks(1);
                yield new LocalDate[]{start, start.plusWeeks(2)};
            }
            case "monthly" -> {
                LocalDate start = referenceDate.withDayOfMonth(1);
                yield new LocalDate[]{start, start.plusMonths(1)};
            }
            case "quarterly" -> {
                int qStartMonth = ((referenceDate.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate start = LocalDate.of(referenceDate.getYear(), qStartMonth, 1);
                yield new LocalDate[]{start, start.plusMonths(3)};
            }
            case "semiannual" -> {
                int mStart = referenceDate.getMonthValue() <= 6 ? 1 : 7;
                LocalDate start = LocalDate.of(referenceDate.getYear(), mStart, 1);
                yield new LocalDate[]{start, start.plusMonths(6)};
            }
            case "annual" -> {
                LocalDate start = LocalDate.of(referenceDate.getYear(), 1, 1);
                yield new LocalDate[]{start, start.plusYears(1)};
            }
            default -> null;
        };
    }

    /** A date guaranteed to fall inside the iteration {@code iterations} periods away from the one
     *  containing {@code referenceDate} — pass straight back into {@link #iterationBounds} to get
     *  that shifted iteration's actual start/end. Negative {@code iterations} moves backward. */
    public static LocalDate shiftReferenceDate(String cadence, LocalDate referenceDate, long iterations) {
        if (cadence == null || referenceDate == null) return null;
        return switch (cadence) {
            case "weekly" -> referenceDate.plusWeeks(iterations);
            case "biweekly" -> referenceDate.plusWeeks(iterations * 2);
            case "monthly" -> referenceDate.plusMonths(iterations);
            case "quarterly" -> referenceDate.plusMonths(iterations * 3);
            case "semiannual" -> referenceDate.plusMonths(iterations * 6);
            case "annual" -> referenceDate.plusYears(iterations);
            default -> null;
        };
    }
}
