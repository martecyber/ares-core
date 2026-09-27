package com.martecyber.ares.aql.registry;

/**
 * The value type a field holds, used to coerce AQL's untyped parsed values before compiling a
 * predicate. {@code PRIORITY} is a dedicated type (rather than reusing NUMBER) for Ares's
 * canonical P0-P4 urgency scale ({@link com.martecyber.ares.common.PriorityThresholds}) — values
 * must be written as "P0".."P4", never raw integers or the old severity vocabulary
 * (critical/high/medium/low/info), so every priority-bearing field across the platform is
 * queried the same way.
 */
public enum AqlFieldType {
    STRING, NUMBER, BOOLEAN, DATE, ENUM, STRING_LIST, PRIORITY
}
