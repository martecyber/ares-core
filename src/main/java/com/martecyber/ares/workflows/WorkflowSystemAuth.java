package com.martecyber.ares.workflows;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.function.Supplier;

/**
 * A workflow run can be started or resumed off a thread with no HTTP request behind it at all —
 * {@code WorkflowCronPoller}'s {@code @Scheduled} sweep, {@code WorkflowStepPoller} resuming a
 * {@code WAITING} step, {@code WorkflowEventDispatcher} firing from a background save with no
 * request thread to inherit an {@code Authentication} from. {@code OrgScopeService} (and every
 * entity service that calls into it — {@code AssetService}/{@code FindingService}/{@code
 * DetectionService}'s {@code listByAql}/{@code countByAql}) unconditionally dereferences {@code
 * SecurityContextHolder}'s current {@code Authentication}, which is {@code null} on such a thread
 * — a real production NPE (see {@code WorkflowRunService#start}/{@code #advance}, the two choke
 * points every workflow execution funnels through, both wrapped with {@link #runAs}).
 * <p>
 * This isn't a privilege escalation: a workflow's own scope (platform/organization/project) is
 * already the real authorization boundary — nothing here lets a run reach data outside whatever
 * scope it was created in — {@code isPlatformAdmin} is just the specific {@code Authentication}
 * shape every one of those service calls unconditionally expects in order to run at all.
 */
final class WorkflowSystemAuth {

    // Deliberately non-numeric: every "createdBy"/"uploadedBy"/etc. call site in the codebase does
    // Long.parseLong(auth.getName()) guarded by a catch that falls back to null on
    // NumberFormatException — a numeric name like "0" would parse "successfully" into a bogus user
    // id that no `user` row actually has, tripping FK constraints (e.g. job.created_by) on every
    // insert made from a workflow-system thread. OrgScopeService.userId is the one unguarded
    // parseLong call site, but it's only reached when isPlatformAdmin(auth) is false — this
    // authority always includes ROLE_MSSP_ADMIN, so that path never executes for this principal.
    private static final Authentication SYSTEM = new UsernamePasswordAuthenticationToken(
        "system", null, List.of(new SimpleGrantedAuthority("ROLE_MSSP_ADMIN")));

    private WorkflowSystemAuth() {}

    /**
     * Runs {@code action} with a synthetic platform-admin {@code Authentication} in place, but
     * only if the calling thread doesn't already have a real one — an HTTP-request-driven caller
     * (the "Run now" button, a synchronous {@code ACTION_CALL_WORKFLOW} fan-out, an event fired
     * from inside a normal request) keeps its own caller's identity untouched. Always restores
     * whatever was there before in a {@code finally} — {@code @Scheduled} threads are pooled and
     * reused, so leaving this set past the call that needed it would leak "platform admin" into
     * whatever runs on that thread next.
     */
    static <T> T runAs(Supplier<T> action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        if (previous != null) {
            return action.get();
        }
        SecurityContextHolder.getContext().setAuthentication(SYSTEM);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(null);
        }
    }

    static void runAs(Runnable action) {
        runAs(() -> {
            action.run();
            return null;
        });
    }
}
