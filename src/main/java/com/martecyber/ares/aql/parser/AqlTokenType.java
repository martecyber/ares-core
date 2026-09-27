package com.martecyber.ares.aql.parser;

public enum AqlTokenType {
    LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA,
    EQ, TILDE_EQ, NEQ, GTE, LTE, GT, LT, MINUS,
    WORD, STRING,
    AND, OR, NOT, HAS,
    EOF
}
