package com.martecyber.ares.aql.parser;

/**
 * Comparison operators AQL leaves may use. EQ also covers "IN" when paired with a list value —
 * {@link #IN} exists as its own, more readable spelling of exactly that same EQ+ListValue path
 * (see {@code PostgresSpecificationCompiler}, which treats the two identically), not a distinct
 * compiled behavior. {@link #HAS} is genuinely new semantics: the
 * *field* is multi-valued and the right-hand side is a single scalar being tested for membership
 * — the inverse shape of IN, only ever legal against an {@code ARRAY_COLUMN}-kind field.
 */
public enum AqlOperator {
    EQ, NEQ, GT, GTE, LT, LTE, CONTAINS, IN, HAS;

    public static AqlOperator fromToken(AqlTokenType type) {
        return switch (type) {
            case EQ -> EQ;
            case NEQ -> NEQ;
            case GT -> GT;
            case GTE -> GTE;
            case LT -> LT;
            case LTE -> LTE;
            case TILDE_EQ -> CONTAINS;
            default -> throw new IllegalArgumentException("Not a comparison operator token: " + type);
        };
    }
}
