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

    /**
     * Ensures a {@link ProjectIteration} row exists for the given label, creating it lazily
     * from the cadence-computed bounds if absent. Returns the (possibly just-created) row.
     */
    public static ProjectIteration ensureIterationRow(Project project, String label,
                                                       ProjectIterationRepository iterRepo) {
        if (label == null) return null;
        return iterRepo.findByProjectIdAndLabel(project.getId(), label).orElseGet(() -> {
            LocalDate[] bounds = iterationBounds(project.getIterationCadence(), labelReferenceDate(project.getIterationCadence(), label));
            if (bounds == null) return null;
            var row = new ProjectIteration(project.getId(), label, bounds[0], bounds[1], bounds[0]);
            return iterRepo.save(row);
        });
    }

    /**
     * Resolves the iteration label for a date using persisted actual dates (with sliding)
     * instead of pure calendar computation. Falls back to {@link #computeLabel} when no
     * matching persisted row exists.
     */
    public static String resolveIterationLabel(Project project, LocalDate date,
                                                ProjectIterationRepository iterRepo) {
        if (project.getIterationCadence() == null || date == null) return null;
        var rows = iterRepo.findByProjectIdOrderByPlannedStartAsc(project.getId());
        for (ProjectIteration row : rows) {
            LocalDate start = row.getActualStart() != null ? row.getActualStart() : row.getPlannedStart();
            LocalDate end = row.effectiveEnd();
            if (!date.isBefore(start) && date.isBefore(end)) return row.getLabel();
        }
        return computeLabel(project.getIterationCadence(), date);
    }

    /**
     * Derives a reference date that falls inside the period described by a label, for use
     * with {@link #iterationBounds}. This is the inverse of {@link #computeLabel}.
     */
    static LocalDate labelReferenceDate(String cadence, String label) {
        if (cadence == null || label == null) return null;
        try {
            int yy = Integer.parseInt(label.substring(0, 2));
            int year = 2000 + yy;
            return switch (cadence) {
                case "weekly" -> {
                    int week = Integer.parseInt(label.substring(4));
                    yield LocalDate.of(year, 1, 4)
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                        .with(DayOfWeek.MONDAY);
                }
                case "biweekly" -> {
                    int fortnight = Integer.parseInt(label.substring(5));
                    int week = (fortnight - 1) * 2 + 1;
                    yield LocalDate.of(year, 1, 4)
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                        .with(DayOfWeek.MONDAY);
                }
                case "monthly" -> {
                    int month = Integer.parseInt(label.substring(3));
                    yield LocalDate.of(year, month, 1);
                }
                case "quarterly" -> {
                    int q = Integer.parseInt(label.substring(4));
                    yield LocalDate.of(year, (q - 1) * 3 + 1, 1);
                }
                case "semiannual" -> {
                    int s = Integer.parseInt(label.substring(4));
                    yield LocalDate.of(year, s == 1 ? 1 : 7, 1);
                }
                case "annual" -> LocalDate.of(year, 1, 1);
                default -> null;
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

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
        return resolveActiveLabel(project, projectRepo, null);
    }

    public static String resolveActiveLabel(Project project, ProjectRepository projectRepo,
                                             ProjectIterationRepository iterRepo) {
        String cadence = project.getIterationCadence();
        if (cadence == null) return null;
        String pending = computeLabel(cadence, LocalDate.now());
        String active = project.getActiveIterationLabel();
        if (active == null || (project.isAutoAdvanceIterations() && !pending.equals(active))) {
            project.setActiveIterationLabel(pending);
            projectRepo.save(project);
            if (iterRepo != null) ensureIterationRow(project, pending, iterRepo);
            return pending;
        }
        if (iterRepo != null) ensureIterationRow(project, active, iterRepo);
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
