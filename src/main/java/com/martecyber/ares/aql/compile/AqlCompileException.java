package com.martecyber.ares.aql.compile;

/** A syntactically valid AQL query that can't be compiled against the target entity/store — e.g. an unsupported operator/value-type combination, or an unresolvable date literal. Mapped to a 400, distinct from {@link com.martecyber.ares.aql.parser.AqlParseException} (grammar errors) and {@link com.martecyber.ares.aql.registry.AqlFieldNotFoundException} (unknown field). */
public class AqlCompileException extends RuntimeException {
    public AqlCompileException(String message) {
        super(message);
    }
}
