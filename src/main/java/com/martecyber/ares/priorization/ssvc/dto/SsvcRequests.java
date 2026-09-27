package com.martecyber.ares.priorization.ssvc.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class SsvcRequests {
    private SsvcRequests() {}

    public record CreateMethodologyRequest(@NotBlank String code, @NotBlank String name, String description) {}

    public record UpdateMethodologyRequest(String name, String description) {}

    public record CreateRoleRequest(@NotBlank String code, @NotBlank String name, String description,
                                     Boolean usesPriorityMapping) {}

    public record UpdateRoleRequest(String code, String name, String description, List<SsvcOutcomeDto> outcomes) {}

    /** Whole-tree replace payload — mirrors SsvcTreeNodeDto's shape but write-side
     *  (no ids; the server assigns fresh ones on every replace). */
    public record TreeNodeInput(
        @NotBlank String nodeType,
        String decisionPointCode,
        String decisionPointName,
        String decisionPointHelp,
        List<@Valid TreeNodeOptionInput> options,
        String outcomeCode,
        String outcomeLabel,
        String outcomeDescription,
        String priorityLevel
    ) {}

    public record TreeNodeOptionInput(
        @NotBlank String code,
        @NotBlank String label,
        String helpText,
        @Valid TreeNodeInput child
    ) {}

    public record ReplaceTreeRequest(@Valid TreeNodeInput root) {}
}
