package com.martecyber.ares.priorization.ssvc.dto;

import java.util.List;

/** Read-side view of a tree node — 'branch' carries decisionPoint/options (each with
 *  a nested child node), 'leaf' carries outcome/priorityLevel. */
public record SsvcTreeNodeDto(
    Long id,
    String nodeType,
    String decisionPointCode,
    String decisionPointName,
    String decisionPointHelp,
    List<SsvcTreeNodeOptionDto> options,
    String outcomeCode,
    String outcomeLabel,
    String outcomeDescription,
    String priorityLevel
) {
    public record SsvcTreeNodeOptionDto(
        Long id,
        String code,
        String label,
        String helpText,
        SsvcTreeNodeDto child
    ) {}
}
