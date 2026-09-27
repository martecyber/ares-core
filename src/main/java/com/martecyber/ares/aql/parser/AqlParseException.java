package com.martecyber.ares.aql.parser;

/** Thrown on malformed AQL input or on an unresolvable field name. Carries the 0-based character position so the API layer can point the user at the exact spot in their query. */
public class AqlParseException extends RuntimeException {

    private final int position;

    public AqlParseException(String message, int position) {
        super(message + " (at position " + position + ")");
        this.position = position;
    }

    public int getPosition() { return position; }
}
