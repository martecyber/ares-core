package com.martecyber.ares.workflows;

/** {@code WorkflowTrigger.triggerType} values. Only MANUAL and CRON are wired to anything in
 *  Phase A; WEBHOOK/EVENT are later phases (see the Workflows implementation plan). */
public final class WorkflowTriggerType {
    public static final String MANUAL = "manual";
    public static final String CRON = "cron";
    public static final String WEBHOOK = "webhook";
    public static final String EVENT = "event";
    public static final String CALL_TOPIC = "call_topic";

    private WorkflowTriggerType() {}
}
