package com.martecyber.ares.workflows;

/** {@code WorkflowRun.status} / {@code WorkflowStepRun.status} values. Step status has two extra
 *  states over run status: {@code waiting} (blocked on an external async op via ref_type/ref_id)
 *  and {@code skipped} (an untaken branch's edge resolved without running). */
public final class WorkflowRunStatus {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private WorkflowRunStatus() {}
}
