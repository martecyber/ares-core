package com.martecyber.ares.aql.parser;

import java.util.List;

/**
 * A value on the right-hand side of a comparison. The parser stays untyped/permissive here —
 * whether "7" means the number 7 or the string "7", or whether "now-7d" is a relative date, is
 * resolved later by the compiler against the target field's declared AqlFieldType.
 */
public sealed interface AqlValue {

    record Scalar(String raw, boolean quoted) implements AqlValue {
        /** Unquoted {@code null} — {@code field == null} / {@code field != null} test for a missing
         *  value on any field type. A quoted {@code "null"} stays the literal string. */
        public boolean isNull() {
            return !quoted && "null".equalsIgnoreCase(raw);
        }
    }

    record ListValue(List<Scalar> items) implements AqlValue { }
}
