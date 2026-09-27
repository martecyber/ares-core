package com.martecyber.ares.jobs;

/** Thrown by async job tasks when they detect their job has been cancelled. */
public class JobCancelledException extends RuntimeException {
    public JobCancelledException(Long jobId) {
        super("Job " + jobId + " was cancelled");
    }
}
