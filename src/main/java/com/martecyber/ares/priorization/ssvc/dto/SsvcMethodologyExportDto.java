package com.martecyber.ares.priorization.ssvc.dto;

import java.util.List;

import com.martecyber.ares.priorization.ssvc.dto.SsvcRequests.TreeNodeInput;

/** Export shape for a whole methodology — every role's metadata, outcome palette, and
 *  full tree (converted to the same write-side TreeNodeInput shape ReplaceTreeRequest
 *  uses), so this is round-trippable back through the existing create endpoints without
 *  a separate import-specific parser. */
public record SsvcMethodologyExportDto(
    String code,
    String name,
    String description,
    List<SsvcRoleExportDto> roles
) {
    public record SsvcRoleExportDto(
        String code,
        String name,
        String description,
        boolean usesPriorityMapping,
        List<SsvcOutcomeDto> outcomes,
        /** Null when the role has no tree yet. */
        TreeNodeInput tree
    ) {}
}
