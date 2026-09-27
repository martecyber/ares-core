package com.martecyber.ares.kb.testing.dto;

/** A guide-point link on a procedure, resolved with the point + guide names for display. */
public record TestingProcedureGuidePointRefDto(
    Long guidePointId,
    String pointTitle,
    Long guideId,
    String guideName
) {}
