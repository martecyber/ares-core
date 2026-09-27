package com.martecyber.ares.priorization.ssvc.dto;

import java.util.List;

public record SsvcMethodologyDto(
    Long id,
    String code,
    String name,
    String description,
    boolean isSystem,
    List<SsvcRoleSummaryDto> roles
) {
    public record SsvcRoleSummaryDto(
        Long id,
        String code,
        String name,
        String description,
        boolean usesPriorityMapping,
        /** True once any finding/template score references a node in this role's tree —
         *  the tree can then only be edited cosmetically (see SsvcMethodologyService). */
        boolean locked,
        /** False when the role has no tree yet (e.g. just created, before its first PUT tree). */
        boolean hasTree
    ) {}
}
