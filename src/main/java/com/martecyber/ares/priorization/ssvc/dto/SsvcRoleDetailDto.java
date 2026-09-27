package com.martecyber.ares.priorization.ssvc.dto;

import java.util.List;

public record SsvcRoleDetailDto(
    Long id,
    Long methodologyId,
    String methodologyCode,
    boolean methodologyIsSystem,
    String code,
    String name,
    String description,
    boolean usesPriorityMapping,
    boolean locked,
    SsvcTreeNodeDto tree,
    List<SsvcOutcomeDto> outcomes
) {}
