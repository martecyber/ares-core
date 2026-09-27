package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.parser.AqlValue;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scalar-literal parsing shared by every AQL evaluator (Postgres/Mongo/in-memory) so NUMBER,
 * BOOLEAN and PRIORITY parsing rules can't drift between them the way DATE parsing once did
 * (see {@link AqlDateLiterals}, which this mirrors).
 */
final class AqlScalarParsing {

    private AqlScalarParsing() {}

    static double parseNumber(AqlValue.Scalar scalar) {
        try {
            return Double.parseDouble(scalar.raw());
        } catch (NumberFormatException e) {
            throw new AqlCompileException("Not a valid number: '" + scalar.raw() + "'");
        }
    }

    static boolean parseBoolean(AqlValue.Scalar scalar) {
        String v = scalar.raw().toLowerCase(Locale.ROOT);
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        throw new AqlCompileException("Not a valid boolean: '" + scalar.raw() + "'");
    }

    private static final Pattern PRIORITY_LABEL = Pattern.compile("[Pp]([0-4])");

    /** "P0".."P4" only — never a raw integer or the old severity vocabulary
     *  (critical/high/medium/low/info). Keeps every priority-bearing field's AQL surface uniform. */
    static int parsePriority(AqlValue.Scalar scalar) {
        var m = PRIORITY_LABEL.matcher(scalar.raw());
        if (!m.matches()) {
            throw new AqlCompileException(
                "Not a valid priority: '" + scalar.raw() + "' (expected one of P0, P1, P2, P3, P4)");
        }
        return Integer.parseInt(m.group(1));
    }
}
