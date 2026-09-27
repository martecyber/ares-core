package com.martecyber.ares.aql.parser;

public record AqlToken(AqlTokenType type, String text, int position) {
}
