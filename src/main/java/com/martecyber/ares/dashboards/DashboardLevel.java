package com.martecyber.ares.dashboards;

/** The 3 tiers a dashboard can be scoped to. {@code scopeId} on {@link Dashboard} is null for
 *  PLATFORM, an organization id for ORGANIZATION, a project id for PROJECT. */
public enum DashboardLevel {
    PLATFORM,
    ORGANIZATION,
    PROJECT
}
