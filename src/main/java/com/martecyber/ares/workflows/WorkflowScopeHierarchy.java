package com.martecyber.ares.workflows;

/**
 * A caller reaches only itself or a scope strictly below it in the platform → organization →
 * project hierarchy — never sideways (another org, another project) or upward. Platform is the
 * root, so it reaches everything; an organization reaches itself and its own projects; a project
 * reaches only itself (it has nothing below it). Shared between {@link WorkflowRunService} (which
 * enforces this at broadcast time) and {@link WorkflowService} (which uses the same rule to
 * suggest which topics are worth autocompleting for a given workflow) so the two can't drift.
 * No DB access here — callers resolve a PROJECT target's owning organization id themselves
 * (typically one {@code ProjectRepository} lookup) and pass it in.
 */
final class WorkflowScopeHierarchy {

    private WorkflowScopeHierarchy() {}

    static boolean isReachable(String callerScopeKind, Long callerScopeId,
                                String targetScopeKind, Long targetScopeId, Long targetProjectOrgId) {
        if (WorkflowScope.PLATFORM.equals(callerScopeKind)) return true;
        if (WorkflowScope.ORGANIZATION.equals(callerScopeKind)) {
            if (WorkflowScope.ORGANIZATION.equals(targetScopeKind)) {
                return callerScopeId.equals(targetScopeId);
            }
            if (WorkflowScope.PROJECT.equals(targetScopeKind)) {
                return targetProjectOrgId != null && targetProjectOrgId.equals(callerScopeId);
            }
            return false;
        }
        if (WorkflowScope.PROJECT.equals(callerScopeKind)) {
            return WorkflowScope.PROJECT.equals(targetScopeKind) && callerScopeId.equals(targetScopeId);
        }
        return false;
    }
}
