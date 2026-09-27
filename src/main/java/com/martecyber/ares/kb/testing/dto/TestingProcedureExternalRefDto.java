package com.martecyber.ares.kb.testing.dto;

import com.martecyber.ares.kb.testing.TestingProcedureExternalRef;

public record TestingProcedureExternalRefDto(
    Long id,
    String refType,
    String refKey,
    String refLabel
) {
    public static TestingProcedureExternalRefDto from(TestingProcedureExternalRef r) {
        return new TestingProcedureExternalRefDto(r.getId(), r.getRefType(), r.getRefKey(), r.getRefLabel());
    }
}
