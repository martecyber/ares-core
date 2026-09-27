package com.martecyber.ares.aql.compile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves AQL DATE-typed literals — ISO date/datetime strings, or the dynamic "now" family
 * ("now", "now-7d", "now+3h", "now-1M", ...) — used by {@link PostgresSpecificationCompiler}.
 */
final class AqlDateLiterals {

    private AqlDateLiterals() {}

    /** "now", or "now" plus a signed integer amount and a single-letter unit. Unit case is
     *  significant only to disambiguate 'm' (minutes) from 'M' (months); "now" itself and the
     *  sign/digits are matched case-insensitively. */
    private static final Pattern RELATIVE = Pattern.compile("(?i:now)(?:([+-])(\\d+)([a-zA-Z]))?");

    /** Epoch milliseconds, exactly 13 digits (true for every timestamp from 2001 to 2286) — how
     *  {@link com.martecyber.ares.aql.compile.AqlVariableExpander} embeds a resolved {@code
     *  {{variable}}} timestamp back into the AQL text. A plain {@code OffsetDateTime.toString()}
     *  can't be used there instead: it contains ':', which isn't a legal WORD-continuation
     *  character in {@link com.martecyber.ares.aql.parser.AqlLexer} (deliberately, so a bare
     *  {@code field:value} Splunk/Kibana-style typo gets a clear error rather than silently
     *  lexing) — an unquoted epoch-millis run of digits sidesteps that without touching the lexer
     *  or requiring the substituted value to be quoted (which would break if the {{...}} reference
     *  was itself already inside a quoted string in the source AQL). */
    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\d{13}");

    static OffsetDateTime resolve(String raw) {
        Matcher m = RELATIVE.matcher(raw);
        if (m.matches()) {
            OffsetDateTime now = OffsetDateTime.now();
            if (m.group(1) == null) {
                return now;
            }
            long amount = Long.parseLong(m.group(2));
            if (m.group(1).equals("-")) {
                amount = -amount;
            }
            return applyOffset(now, amount, m.group(3).charAt(0), raw);
        }
        if (EPOCH_MILLIS.matcher(raw).matches()) {
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(raw)), ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                throw new AqlCompileException("Not a valid date literal: '" + raw
                    + "' (expected ISO date/datetime, 'now', or a relative offset like 'now-7d'/'now+3h')");
            }
        }
    }

    /** Package-visible (not private) so {@link AqlVariableExpander} can reuse the same s/m/h/d/w/M/y
     *  arithmetic for {@code {{variable}}±N<unit>} expressions instead of duplicating it. */
    static OffsetDateTime applyOffset(OffsetDateTime base, long amount, char unit, String raw) {
        return switch (unit) {
            case 's' -> base.plusSeconds(amount);
            case 'm' -> base.plusMinutes(amount);
            case 'h', 'H' -> base.plusHours(amount);
            case 'd', 'D' -> base.plusDays(amount);
            case 'w', 'W' -> base.plusWeeks(amount);
            case 'M' -> base.plusMonths(amount);
            case 'y', 'Y' -> base.plusYears(amount);
            default -> throw new AqlCompileException("Unknown relative date unit '" + unit + "' in '" + raw
                + "' — expected one of s/m/h/d/w/M/y (seconds/minutes/hours/days/weeks/Months/years)");
        };
    }
}
